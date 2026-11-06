import http from 'node:http';
import type { AddressInfo } from 'node:net';
import os from 'node:os';
import path from 'node:path';
import fs from 'node:fs';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { buildApp, type BuiltApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { totpCode } from '../src/lib/crypto.js';

let built: BuiltApp;
let mock: http.Server;
let mockUrl = '';
let baseUrl = '';

const M3U = `#EXTM3U url-tvg="http://epg.example/guide.xml"
#EXTINF:-1 tvg-id="one" group-title="News",Kanal 1
http://stream.example/1.ts
#EXTINF:-1 tvg-id="two" group-title="Sport",Kanal 2
http://stream.example/2.ts
`;

beforeAll(async () => {
  mock = http.createServer((req, res) => {
    const u = new URL(req.url!, 'http://x');
    if (u.pathname === '/list.m3u') return res.end(M3U);
    if (u.pathname === '/player_api.php') {
      const ok = u.searchParams.get('username') === 'user' && u.searchParams.get('password') === 'pass';
      const action = u.searchParams.get('action');
      res.setHeader('content-type', 'application/json');
      if (action) return res.end(JSON.stringify([{ category_id: '1' }, { category_id: '2' }]));
      return res.end(JSON.stringify({ user_info: { auth: ok ? 1 : 0, status: 'Active', exp_date: '1900000000', max_connections: '2' } }));
    }
    res.statusCode = 404;
    res.end('no');
  });
  await new Promise<void>((r) => mock.listen(0, '127.0.0.1', () => r()));
  mockUrl = `http://127.0.0.1:${(mock.address() as AddressInfo).port}`;

  process.env.ADMIN_USERNAME = 'boss';
  process.env.ADMIN_PASSWORD = 'very-secret-password';
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'orbit-test-'));
  const config = loadConfig({
    databaseUrl: 'pglite:memory',
    encryptionKey: Buffer.alloc(32, 7),
    appSecret: 'x'.repeat(40),
    dataDir,
    webDir: null,
    allowPrivateUrls: true,
    publicUrl: 'http://localhost:9999',
    logLevel: 'silent',
  });
  built = await buildApp(config, { logger: false });
  await built.app.listen({ port: 0, host: '127.0.0.1' });
  baseUrl = `http://127.0.0.1:${(built.app.server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  await built?.close();
  mock?.close();
});

async function api(method: string, url: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await built.app.inject({ method: method as any, url, payload: body as any, headers });
  let json: any = null;
  try {
    json = res.json();
  } catch {
    /* ignore */
  }
  return { status: res.statusCode, json, headers: res.headers, raw: res };
}

async function registerDevice(extra: Record<string, unknown> = {}) {
  const r = await api('POST', '/api/device/register', { isTv: true, manufacturer: 'Xiaomi', model: 'Mi Box S', sdkInt: 28, appVersionCode: 1, appVersionName: '0.1.0', ...extra });
  expect(r.status).toBe(200);
  return { id: r.json.deviceId as string, auth: { authorization: `Device ${r.json.auth}` } };
}

let adminCookie = '';
async function login(username = 'boss', password = 'very-secret-password', totp?: string) {
  const r = await api('POST', '/api/admin/auth/login', { username, password, totp });
  const cookie = (r.headers['set-cookie'] as string | string[] | undefined) ?? '';
  const c = Array.isArray(cookie) ? cookie[0] : cookie;
  return { r, cookie: c ? c.split(';')[0]! : '' };
}

describe('smer A — korisnik povezuje TV preko sajta', () => {
  it('uređaj dobija kod, sajt dodaje listu, uređaj je vidi', async () => {
    const dev = await registerDevice();
    const code = await api('POST', '/api/device/pairing-code', {}, dev.auth);
    expect(code.status).toBe(200);
    expect(code.json.code).toMatch(/^[1-9]\d{7}$/);
    expect(code.json.linkUrl).toContain(`/link?code=${code.json.code}`);

    const bad = await api('POST', '/api/public/link/start', { code: '12345678' });
    expect(bad.status).toBe(404);

    const start = await api('POST', '/api/public/link/start', { code: `${code.json.code.slice(0, 4)} ${code.json.code.slice(4)}` });
    expect(start.status).toBe(200);
    const session = { authorization: `Bearer ${start.json.sessionToken}` };

    const wrong = await api('POST', '/api/public/link/playlists', { playlist: { type: 'xtream', name: 'X', server: mockUrl, username: 'user', password: 'nope' } }, session);
    expect(wrong.json.saved).toBe(false);
    expect(wrong.json.test.message).toMatch(/Pogrešno/);

    const add = await api('POST', '/api/public/link/playlists', { playlist: { type: 'm3u', name: 'Moja lista', url: `${mockUrl}/list.m3u?username=a&password=secret` } }, session);
    expect(add.status).toBe(200);
    expect(add.json.saved).toBe(true);
    expect(add.json.test.info.channels).toBe(2);
    expect(add.json.playlist.url).not.toContain('secret');

    const cfg = await api('GET', '/api/device/config', undefined, dev.auth);
    expect(cfg.json.playlists).toHaveLength(1);
    expect(cfg.json.playlists[0].url).toContain('password=secret');
    expect(cfg.json.settings.player.engine).toBe('exo');

    const del = await api('DELETE', `/api/public/link/playlists/${add.json.playlist.id}`, undefined, session);
    expect(del.status).toBe(200);
    const cfg2 = await api('GET', '/api/device/config', undefined, dev.auth);
    expect(cfg2.json.playlists).toHaveLength(0);
  });

  it('odbija lokalne adrese kada nisu dozvoljene', async () => {
    built.ctx.config.allowPrivateUrls = false;
    try {
      const dev = await registerDevice();
      const code = await api('POST', '/api/device/pairing-code', {}, dev.auth);
      const start = await api('POST', '/api/public/link/start', { code: code.json.code });
      const r = await api('POST', '/api/public/link/test', { playlist: { type: 'm3u', name: 'x', url: `${mockUrl}/list.m3u` } }, { authorization: `Bearer ${start.json.sessionToken}` });
      expect(r.status).toBe(400);
      expect(r.json.error).toBe('private_url');
    } finally {
      built.ctx.config.allowPrivateUrls = true;
    }
  });

  it('neispravan token uređaja je odbijen', async () => {
    const r = await api('GET', '/api/device/config', undefined, { authorization: 'Device 00000000-0000-0000-0000-000000000000.abc' });
    expect(r.status).toBe(401);
  });
});

describe('admin', () => {
  it('prijava i glavni admin sa početka', async () => {
    const bad = await login('boss', 'wrong-password-123');
    expect(bad.r.status).toBe(401);
    const ok = await login();
    expect(ok.r.status).toBe(200);
    adminCookie = ok.cookie;
    const me = await api('GET', '/api/admin/auth/me', undefined, { cookie: adminCookie });
    expect(me.json.admin.role).toBe('super');
    const noAuth = await api('GET', '/api/admin/devices');
    expect(noAuth.status).toBe(401);
  });

  it('smer B — admin pravi profil i kod, uređaj se aktivira', async () => {
    const c = { cookie: adminCookie };
    const created = await api('POST', '/api/admin/profiles', {
      name: 'Petar Petrović',
      maxDevices: 1,
      settings: { player: { engine: 'vlc', unknownKey: 1 }, hacker: true },
      playlists: [{ type: 'xtream', name: 'Glavna', server: `${mockUrl}/player_api.php`, username: 'user', password: 'pass' }],
    }, c);
    expect(created.status).toBe(200);
    const pid = created.json.profile.id;
    expect(created.json.profile.settings).toEqual({ player: { engine: 'vlc' } });

    const test = await api('POST', '/api/admin/playlists/test', { playlist: { type: 'xtream', name: 'x', server: mockUrl, username: 'user', password: 'pass' } }, c);
    expect(test.json.ok).toBe(true);
    expect(test.json.info.liveCategories).toBe(2);

    const ac = await api('POST', `/api/admin/profiles/${pid}/activation-codes`, { ttlHours: 24, maxUses: 2 }, c);
    expect(ac.status).toBe(200);

    const dev = await registerDevice();
    const act = await api('POST', '/api/device/activate', { code: ac.json.code }, dev.auth);
    expect(act.status).toBe(200);
    expect(act.json.profile.name).toBe('Petar Petrović');
    expect(act.json.playlists[0]).toMatchObject({ type: 'xtream', username: 'user', password: 'pass', server: mockUrl });
    expect(act.json.settings.player.engine).toBe('vlc');

    // drugi uređaj: kod ima još jednu upotrebu, ali profil dozvoljava samo 1 uređaj
    const dev2 = await registerDevice();
    const act2 = await api('POST', '/api/device/activate', { code: ac.json.code }, dev2.auth);
    expect(act2.status).toBe(409);

    // admin produži broj uređaja i postavi istek u prošlost
    await api('PATCH', `/api/admin/profiles/${pid}`, { maxDevices: 3, expiresAt: '2020-01-01T00:00:00Z' }, c);
    const exp = await api('GET', '/api/device/config', undefined, dev.auth);
    expect(exp.json.profile.expired).toBe(true);
    expect(exp.json.playlists).toHaveLength(0);
  });

  it('admin povezuje uređaj kodom sa TV ekrana (claim)', async () => {
    const c = { cookie: adminCookie };
    const p = await api('POST', '/api/admin/profiles', { name: 'Hotel sobe', maxDevices: 10 }, c);
    const dev = await registerDevice();
    const code = await api('POST', '/api/device/pairing-code', {}, dev.auth);
    const claim = await api('POST', '/api/admin/devices/claim', { code: code.json.code, profileId: p.json.profile.id, name: 'Soba 101' }, c);
    expect(claim.status).toBe(200);
    expect(claim.json.deviceId).toBe(dev.id);
    const detail = await api('GET', `/api/admin/devices/${dev.id}`, undefined, c);
    expect(detail.json.device.name).toBe('Soba 101');
    expect(detail.json.profile.name).toBe('Hotel sobe');
  });

  it('launcher profil sa PIN-om stiže na uređaj samo kao heš', async () => {
    const c = { cookie: adminCookie };
    const l = await api('POST', '/api/admin/launcher-profiles', {
      name: 'Kiosk',
      config: { enabled: true, locked: true, kioskLevel: 'device_owner', blockSettings: true, exitPin: '4321', allowedApps: [{ package: 'com.google.android.youtube.tv', label: 'YouTube' }] },
    }, c);
    expect(l.status).toBe(200);
    expect(l.json.launcher.config.hasExitPin).toBe(true);
    expect(JSON.stringify(l.json)).not.toContain('4321');

    const dev = await registerDevice();
    await api('PATCH', `/api/admin/devices/${dev.id}`, { launcherProfileId: l.json.launcher.id }, c);
    const cfg = await api('GET', '/api/device/config', undefined, dev.auth);
    expect(cfg.json.launcher.locked).toBe(true);
    expect(cfg.json.launcher.allowedApps[0].package).toBe('com.google.android.youtube.tv');
    expect(cfg.json.launcher.exitPinHash).toMatch(/^[0-9a-f]{64}$/);

    // izmena bez novog PIN-a zadržava stari
    const upd = await api('PUT', `/api/admin/launcher-profiles/${l.json.launcher.id}`, { name: 'Kiosk 2', config: { enabled: true } }, c);
    expect(upd.json.launcher.config.hasExitPin).toBe(true);
    const cleared = await api('PUT', `/api/admin/launcher-profiles/${l.json.launcher.id}`, { name: 'Kiosk 2', config: { enabled: true, clearExitPin: true } }, c);
    expect(cleared.json.launcher.config.hasExitPin).toBe(false);
  });

  it('komande stižu preko WebSocket-a, a rezervno preko heartbeat-a', async () => {
    const c = { cookie: adminCookie };
    const dev = await registerDevice();

    // uređaj nije na mreži: komanda čeka
    const pending = await api('POST', `/api/admin/devices/${dev.id}/commands`, { type: 'show_message', payload: { text: 'Zdravo' } }, c);
    expect(pending.json.command.status).toBe('pending');
    const hb = await api('POST', '/api/device/heartbeat', { status: { screen: 'live' } }, dev.auth);
    expect(hb.json.commands.map((x: any) => x.type)).toEqual(['show_message']);

    // uređaj na mreži: komanda stiže odmah
    const ws = new WebSocket(`${baseUrl.replace('http', 'ws')}/api/device/ws`, { headers: dev.auth });
    const messages: any[] = [];
    await new Promise<void>((resolve, reject) => {
      ws.on('message', (m) => {
        messages.push(JSON.parse(m.toString()));
        if (messages.length === 1) resolve();
      });
      ws.on('error', reject);
    });
    expect(messages[0].type).toBe('hello');
    const gotCommand = new Promise<any>((resolve) => ws.on('message', (m) => resolve(JSON.parse(m.toString()))));
    const live = await api('POST', `/api/admin/devices/${dev.id}/commands`, { type: 'restart_app' }, c);
    expect(live.json.command.status).toBe('sent');
    const msg = await gotCommand;
    expect(msg.command.type).toBe('restart_app');
    ws.send(JSON.stringify({ type: 'command_result', id: msg.command.id, ok: true, result: { restarted: true } }));
    await new Promise((r) => setTimeout(r, 100));
    const detail = await api('GET', `/api/admin/devices/${dev.id}`, undefined, c);
    expect(detail.json.device.online).toBe(true);
    expect(detail.json.commands.find((x: any) => x.id === msg.command.id).status).toBe('done');

    const list = await api('GET', '/api/admin/devices?status=online', undefined, c);
    expect(list.json.items.map((d: any) => d.id)).toContain(dev.id);
    ws.close();
  });

  it('objava nove verzije i provera ažuriranja', async () => {
    const boundary = '----orbit';
    const parts = [
      ['flavor', 'pro'],
      ['versionCode', '5'],
      ['versionName', '0.5.0'],
      ['changelog', 'Novo'],
    ]
      .map(([k, v]) => `--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`)
      .join('');
    const body = Buffer.concat([
      Buffer.from(parts),
      Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="app.apk"\r\nContent-Type: application/octet-stream\r\n\r\n`),
      Buffer.from('FAKE-APK-CONTENT'),
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]);
    const up = await api('POST', '/api/admin/releases', body, { cookie: adminCookie, 'content-type': `multipart/form-data; boundary=${boundary}` });
    expect(up.status).toBe(200);
    expect(up.json.release.size).toBe(16);

    const dev = await registerDevice({ appVersionCode: 1 });
    const upd = await api('GET', '/api/device/update', undefined, dev.auth);
    expect(upd.json.available).toBe(true);
    expect(upd.json.release.versionCode).toBe(5);
    const dl = await built.app.inject({ method: 'GET', url: `/api/device/releases/${upd.json.release.id}/download`, headers: dev.auth });
    expect(dl.body).toBe('FAKE-APK-CONTENT');

    const pub = await api('GET', '/api/public/info');
    expect(pub.json.downloads.pro.versionName).toBe('0.5.0');
    const pubDl = await built.app.inject({ method: 'GET', url: '/api/public/download/pro' });
    expect(pubDl.statusCode).toBe(200);
    expect(pubDl.headers['content-disposition']).toContain('.apk');
  });

  it('reseler vidi samo svoje profile i uređaje', async () => {
    const c = { cookie: adminCookie };
    const r = await api('POST', '/api/admin/admins', { username: 'reseller1', password: 'reseller-password', role: 'reseller' }, c);
    expect(r.status).toBe(200);
    const rs = await login('reseller1', 'reseller-password');
    const rc = { cookie: rs.cookie };
    const own = await api('POST', '/api/admin/profiles', { name: 'Reselerov klijent' }, rc);
    expect(own.status).toBe(200);
    const list = await api('GET', '/api/admin/profiles', undefined, rc);
    expect(list.json.items.map((p: any) => p.name)).toEqual(['Reselerov klijent']);
    const devices = await api('GET', '/api/admin/devices', undefined, rc);
    expect(devices.json.items).toHaveLength(0);
    const settings = await api('PUT', '/api/admin/settings', { settings: {} }, rc);
    expect(settings.status).toBe(403);
  });

  it('dvofaktorska prijava', async () => {
    const c = { cookie: adminCookie };
    const setup = await api('POST', '/api/admin/auth/totp/setup', {}, c);
    const en = await api('POST', '/api/admin/auth/totp/enable', { secret: setup.json.secret, code: totpCode(setup.json.secret) }, c);
    expect(en.status).toBe(200);
    const noCode = await login();
    expect(noCode.r.json.error).toBe('totp_required');
    const withCode = await login('boss', 'very-secret-password', totpCode(setup.json.secret));
    expect(withCode.r.status).toBe(200);
  });

  it('globalna podešavanja se spajaju sa profilom i uređajem', async () => {
    const c = { cookie: adminCookie };
    await api('PUT', '/api/admin/settings', { settings: { ui: { theme: 'light' }, player: { bufferMs: 30000 } } }, c);
    const dev = await registerDevice();
    await api('PATCH', `/api/admin/devices/${dev.id}`, { settings: { player: { bufferMs: 5000 } } }, c);
    const cfg = await api('GET', '/api/device/config', undefined, dev.auth);
    expect(cfg.json.settings.ui.theme).toBe('light');
    expect(cfg.json.settings.player.bufferMs).toBe(5000);
    expect(cfg.json.settings.player.engine).toBe('exo');
    const dash = await api('GET', '/api/admin/dashboard', undefined, c);
    expect(dash.json.devices.total).toBeGreaterThan(3);
    const auditLog = await api('GET', '/api/admin/audit', undefined, c);
    expect(auditLog.json.items.length).toBeGreaterThan(5);
  });
});
