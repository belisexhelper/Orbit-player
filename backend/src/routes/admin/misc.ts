import fs from 'node:fs';
import crypto from 'node:crypto';
import { pipeline } from 'node:stream/promises';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { badRequest, conflict, forbidden, notFound } from '../../lib/errors.js';
import { DEFAULT_SETTINGS, prepareLauncherConfig, sanitizeSettings, type LauncherConfig } from '../../lib/settings.js';
import { createAdmin } from '../../services/admins.js';
import { audit } from '../../services/audit.js';
import type { Ctx } from '../../services/context.js';
import { getGlobalSettings, notifyConfigChanged } from '../../services/devices.js';
import { releasePath } from '../device.js';
import { requireRole } from './auth.js';
import { getLauncherOr404, isReseller } from './scope.js';
import { hashPassword } from '../../lib/crypto.js';

const uuid = z.string().uuid();

function launcherOut(l: any) {
  const cfg = { ...(l.config as LauncherConfig) } as any;
  const hasPin = !!cfg.exitPinHash;
  delete cfg.exitPinHash;
  return { id: l.id, name: l.name, config: { ...cfg, hasExitPin: hasPin }, global: l.owner_admin_id === null, createdAt: l.created_at, updatedAt: l.updated_at, deviceCount: l.device_count };
}

export function miscRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    // ---------- Kontrolna tabla ----------
    app.get('/dashboard', async (req) => {
      const own = isReseller(req) ? `AND p.owner_admin_id = '${req.admin!.id}'` : '';
      const ownP = isReseller(req) ? `WHERE owner_admin_id = '${req.admin!.id}'` : '';
      const online = ctx.hub.onlineIds();
      const [devices, profiles, versions, errors] = await Promise.all([
        ctx.db.query(
          `SELECT count(*)::int AS total,
             count(*) FILTER (WHERE d.id = ANY($1::uuid[]))::int AS online,
             count(*) FILTER (WHERE d.last_seen_at > now() - interval '24 hours')::int AS seen24h,
             count(*) FILTER (WHERE d.created_at > now() - interval '7 days')::int AS new7d,
             count(*) FILTER (WHERE d.profile_id IS NULL)::int AS unassigned,
             count(*) FILTER (WHERE d.is_tv)::int AS tv
           FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id WHERE true ${own}`,
          [online],
        ),
        ctx.db.query(
          `SELECT count(*)::int AS total,
             count(*) FILTER (WHERE expires_at < now())::int AS expired,
             count(*) FILTER (WHERE expires_at BETWEEN now() AND now() + interval '7 days')::int AS expiring7d
           FROM profiles ${ownP}`,
        ),
        ctx.db.query(
          `SELECT d.app_version_name AS version, d.flavor, count(*)::int AS n FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id
           WHERE d.last_seen_at > now() - interval '30 days' ${own} GROUP BY 1, 2 ORDER BY n DESC LIMIT 10`,
        ),
        ctx.db.query(
          `SELECT count(*)::int AS n FROM device_logs l JOIN devices d ON d.id = l.device_id LEFT JOIN profiles p ON p.id = d.profile_id
           WHERE l.level IN ('error','crash') AND l.created_at > now() - interval '24 hours' ${own}`,
        ),
      ]);
      return { devices: devices.rows[0], profiles: profiles.rows[0], versions: versions.rows, errors24h: errors.rows[0]?.n ?? 0 };
    });

    // ---------- Launcher profili ----------
    app.get('/launcher-profiles', async (req) => {
      const w = isReseller(req) ? `WHERE l.owner_admin_id IS NULL OR l.owner_admin_id = $1` : '';
      const { rows } = await ctx.db.query(
        `SELECT l.*, (SELECT count(*)::int FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id
            WHERE COALESCE(d.launcher_profile_id, p.launcher_profile_id) = l.id) AS device_count
         FROM launcher_profiles l ${w} ORDER BY l.name`,
        isReseller(req) ? [req.admin!.id] : [],
      );
      return { items: rows.map(launcherOut) };
    });

    app.get('/launcher-profiles/:id', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      return { launcher: launcherOut(await getLauncherOr404(ctx, req, id)) };
    });

    app.post('/launcher-profiles', async (req) => {
      requireRole(req, 'super', 'reseller');
      const body = z.object({ name: z.string().trim().min(1).max(80), config: z.unknown() }).parse(req.body ?? {});
      const cfg = prepareLauncherConfig(body.config ?? {});
      // super admin pravi globalne profile (vidljive i reselerima), reseler svoje
      const owner = req.admin!.role === 'super' ? null : req.admin!.id;
      const { rows } = await ctx.db.query(`INSERT INTO launcher_profiles (name, config, owner_admin_id) VALUES ($1, $2, $3) RETURNING *`, [
        body.name,
        JSON.stringify(cfg),
        owner,
      ]);
      await audit(ctx, req.admin!, 'launcher_created', rows[0].id, { name: body.name }, req.ip);
      return { launcher: launcherOut(rows[0]) };
    });

    app.put('/launcher-profiles/:id', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const l = await getLauncherOr404(ctx, req, id);
      if (isReseller(req) && l.owner_admin_id !== req.admin!.id) throw forbidden('Globalne launcher profile menja samo glavni admin');
      const body = z.object({ name: z.string().trim().min(1).max(80), config: z.unknown() }).parse(req.body ?? {});
      const cfg = prepareLauncherConfig(body.config ?? {}, l.config);
      const { rows } = await ctx.db.query(
        `UPDATE launcher_profiles SET name = $2, config = $3, updated_at = now() WHERE id = $1 RETURNING *`,
        [id, body.name, JSON.stringify(cfg)],
      );
      await notifyConfigChanged(ctx, { launcherProfileId: id });
      await audit(ctx, req.admin!, 'launcher_updated', id, { name: body.name }, req.ip);
      return { launcher: launcherOut(rows[0]) };
    });

    app.delete('/launcher-profiles/:id', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const l = await getLauncherOr404(ctx, req, id);
      if (isReseller(req) && l.owner_admin_id !== req.admin!.id) throw forbidden();
      // prvo obavestimo uređaje dok je veza još u bazi
      const { rows } = await ctx.db.query<{ id: string }>(
        `SELECT d.id FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id WHERE COALESCE(d.launcher_profile_id, p.launcher_profile_id) = $1`,
        [id],
      );
      await ctx.db.query(`DELETE FROM launcher_profiles WHERE id = $1`, [id]);
      await notifyConfigChanged(ctx, { deviceIds: rows.map((r) => r.id) });
      await audit(ctx, req.admin!, 'launcher_deleted', id, { name: l.name }, req.ip);
      return { ok: true };
    });

    // ---------- Globalna podešavanja aplikacije ----------
    app.get('/settings', async () => {
      const site = await ctx.db.query(`SELECT value FROM settings WHERE key = 'site'`);
      return { defaults: DEFAULT_SETTINGS, global: await getGlobalSettings(ctx), site: site.rows[0]?.value ?? {} };
    });

    app.put('/settings', async (req) => {
      requireRole(req, 'super');
      const body = z.object({ settings: z.record(z.string(), z.unknown()) }).parse(req.body ?? {});
      const clean = sanitizeSettings(body.settings);
      await ctx.db.query(
        `INSERT INTO settings (key, value) VALUES ('app', $1) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
        [JSON.stringify(clean)],
      );
      await notifyConfigChanged(ctx, { all: true });
      await audit(ctx, req.admin!, 'settings_updated', 'app', null, req.ip);
      return { global: clean };
    });

    app.put('/settings/site', async (req) => {
      requireRole(req, 'super');
      const body = z
        .object({
          supportContact: z.string().max(200).default(''),
          announcement: z.string().max(1000).default(''),
          companyName: z.string().max(200).default(''),
        })
        .parse(req.body ?? {});
      await ctx.db.query(
        `INSERT INTO settings (key, value) VALUES ('site', $1) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()`,
        [JSON.stringify(body)],
      );
      await audit(ctx, req.admin!, 'site_settings_updated', 'site', null, req.ip);
      return { site: body };
    });

    // ---------- Verzije aplikacije (OTA) ----------
    app.get('/releases', async (req) => {
      requireRole(req, 'super', 'support');
      const { rows } = await ctx.db.query(`SELECT * FROM releases ORDER BY flavor, version_code DESC`);
      return { items: rows.map(releaseOut) };
    });

    app.post('/releases', async (req) => {
      requireRole(req, 'super');
      const fields: Record<string, string> = {};
      let saved: { name: string; size: number; sha256: string } | null = null;
      for await (const part of req.parts()) {
        if (part.type === 'file') {
          if (!/\.apk$/i.test(part.filename)) throw badRequest('Fajl mora biti .apk');
          const name = `${Date.now()}-${crypto.randomBytes(4).toString('hex')}.apk`;
          const dest = releasePath(ctx, name);
          const hash = crypto.createHash('sha256');
          let size = 0;
          part.file.on('data', (c: Buffer) => {
            hash.update(c);
            size += c.length;
          });
          await pipeline(part.file, fs.createWriteStream(dest));
          if (part.file.truncated) {
            fs.rmSync(dest, { force: true });
            throw badRequest('Fajl je prevelik');
          }
          saved = { name, size, sha256: hash.digest('hex') };
        } else {
          fields[part.fieldname] = String(part.value ?? '');
        }
      }
      if (!saved) throw badRequest('Izaberite APK fajl');
      try {
        const meta = z
          .object({
            flavor: z.enum(['pro', 'lite']),
            channel: z.enum(['stable', 'beta']).default('stable'),
            versionCode: z.coerce.number().int().min(1),
            versionName: z.string().trim().min(1).max(40),
            changelog: z.string().max(5000).default(''),
            rolloutPercent: z.coerce.number().int().min(0).max(100).default(100),
            mandatory: z.enum(['true', 'false']).default('false').transform((v) => v === 'true'),
            minSdk: z.coerce.number().int().min(21).max(40).default(21),
          })
          .parse(fields);
        const exists = await ctx.db.query(`SELECT 1 FROM releases WHERE flavor = $1 AND version_code = $2`, [meta.flavor, meta.versionCode]);
        if (exists.rows[0]) throw conflict(`Verzija ${meta.versionCode} za ${meta.flavor} već postoji`);
        const { rows } = await ctx.db.query(
          `INSERT INTO releases (flavor, channel, version_code, version_name, file_name, size, sha256, changelog, rollout_percent, mandatory, min_sdk)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) RETURNING *`,
          [meta.flavor, meta.channel, meta.versionCode, meta.versionName, saved.name, saved.size, saved.sha256, meta.changelog, meta.rolloutPercent, meta.mandatory, meta.minSdk],
        );
        await audit(ctx, req.admin!, 'release_uploaded', rows[0].id, { flavor: meta.flavor, version: meta.versionName }, req.ip);
        return { release: releaseOut(rows[0]) };
      } catch (e) {
        fs.rmSync(releasePath(ctx, saved.name), { force: true });
        throw e;
      }
    });

    app.patch('/releases/:id', async (req) => {
      requireRole(req, 'super');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const body = z
        .object({
          channel: z.enum(['stable', 'beta']).optional(),
          changelog: z.string().max(5000).optional(),
          rolloutPercent: z.number().int().min(0).max(100).optional(),
          mandatory: z.boolean().optional(),
        })
        .parse(req.body ?? {});
      const { rows } = await ctx.db.query(
        `UPDATE releases SET channel = COALESCE($2, channel), changelog = COALESCE($3, changelog),
           rollout_percent = COALESCE($4, rollout_percent), mandatory = COALESCE($5, mandatory)
         WHERE id = $1 RETURNING *`,
        [id, body.channel ?? null, body.changelog ?? null, body.rolloutPercent ?? null, body.mandatory ?? null],
      );
      if (!rows[0]) throw notFound();
      await audit(ctx, req.admin!, 'release_updated', id, body as any, req.ip);
      return { release: releaseOut(rows[0]) };
    });

    app.delete('/releases/:id', async (req) => {
      requireRole(req, 'super');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const { rows } = await ctx.db.query(`DELETE FROM releases WHERE id = $1 RETURNING *`, [id]);
      if (!rows[0]) throw notFound();
      fs.rmSync(releasePath(ctx, rows[0].file_name), { force: true });
      await audit(ctx, req.admin!, 'release_deleted', id, { version: rows[0].version_name }, req.ip);
      return { ok: true };
    });

    // ---------- Admin nalozi ----------
    app.get('/admins', async (req) => {
      requireRole(req, 'super');
      const { rows } = await ctx.db.query(
        `SELECT id, username, role, disabled, totp_secret IS NOT NULL AS totp, created_at, last_login_at FROM admins ORDER BY created_at`,
      );
      return {
        items: rows.map((a) => ({ id: a.id, username: a.username, role: a.role, disabled: a.disabled, totpEnabled: a.totp, createdAt: a.created_at, lastLoginAt: a.last_login_at })),
      };
    });

    app.post('/admins', async (req) => {
      requireRole(req, 'super');
      const body = z
        .object({
          username: z.string().trim().min(3).max(50).regex(/^[a-zA-Z0-9._-]+$/, 'Samo slova, brojevi, tačka, crtica i donja crta'),
          password: z.string().min(10, 'Lozinka mora imati bar 10 znakova').max(200),
          role: z.enum(['super', 'support', 'reseller']),
        })
        .parse(req.body ?? {});
      const exists = await ctx.db.query(`SELECT 1 FROM admins WHERE username = $1`, [body.username.toLowerCase()]);
      if (exists.rows[0]) throw conflict('Korisničko ime je zauzeto');
      const a = await createAdmin(ctx, body.username, body.password, body.role);
      await audit(ctx, req.admin!, 'admin_created', a.id, { username: a.username, role: a.role }, req.ip);
      return { admin: a };
    });

    app.patch('/admins/:id', async (req) => {
      requireRole(req, 'super');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const body = z
        .object({
          role: z.enum(['super', 'support', 'reseller']).optional(),
          disabled: z.boolean().optional(),
          password: z.string().min(10).max(200).optional(),
          resetTotp: z.boolean().optional(),
        })
        .parse(req.body ?? {});
      if (id === req.admin!.id && (body.disabled || (body.role && body.role !== 'super'))) {
        throw badRequest('Ne možete sebi oduzeti prava ili isključiti svoj nalog');
      }
      if (body.role !== undefined) await ctx.db.query(`UPDATE admins SET role = $2 WHERE id = $1`, [id, body.role]);
      if (body.disabled !== undefined) await ctx.db.query(`UPDATE admins SET disabled = $2 WHERE id = $1`, [id, body.disabled]);
      if (body.password) await ctx.db.query(`UPDATE admins SET password_hash = $2, failed_logins = 0, locked_until = NULL WHERE id = $1`, [id, await hashPassword(body.password)]);
      if (body.resetTotp) await ctx.db.query(`UPDATE admins SET totp_secret = NULL WHERE id = $1`, [id]);
      await audit(ctx, req.admin!, 'admin_updated', id, { ...body, password: body.password ? '***' : undefined }, req.ip);
      return { ok: true };
    });

    app.delete('/admins/:id', async (req) => {
      requireRole(req, 'super');
      const { id } = z.object({ id: uuid }).parse(req.params);
      if (id === req.admin!.id) throw badRequest('Ne možete obrisati svoj nalog');
      await ctx.db.query(`DELETE FROM admins WHERE id = $1`, [id]);
      await audit(ctx, req.admin!, 'admin_deleted', id, null, req.ip);
      return { ok: true };
    });

    // ---------- Revizija (ko je šta radio) ----------
    app.get('/audit', async (req) => {
      requireRole(req, 'super');
      const q = z.object({ limit: z.coerce.number().int().min(1).max(500).default(100), before: z.coerce.number().int().optional(), q: z.string().max(100).optional() }).parse(req.query ?? {});
      const { rows } = await ctx.db.query(
        `SELECT * FROM audit_log WHERE ($1::bigint IS NULL OR id < $1) AND ($2::text IS NULL OR action ILIKE $2 OR admin_username ILIKE $2 OR target ILIKE $2)
         ORDER BY id DESC LIMIT $3`,
        [q.before ?? null, q.q ? `%${q.q}%` : null, q.limit],
      );
      return {
        items: rows.map((r) => ({ id: Number(r.id), admin: r.admin_username, action: r.action, target: r.target, details: r.details, ip: r.ip, createdAt: r.created_at })),
      };
    });
  };
}

function releaseOut(r: any) {
  return {
    id: r.id,
    flavor: r.flavor,
    channel: r.channel,
    versionCode: r.version_code,
    versionName: r.version_name,
    size: Number(r.size),
    sha256: r.sha256,
    changelog: r.changelog,
    rolloutPercent: r.rollout_percent,
    mandatory: r.mandatory,
    minSdk: r.min_sdk,
    createdAt: r.created_at,
  };
}
