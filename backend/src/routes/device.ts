import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { randomToken, safeEqual, sha256 } from '../lib/crypto.js';
import { notFound, unauthorized, forbidden } from '../lib/errors.js';
import type { Ctx } from '../services/context.js';
import { buildDeviceConfig, type DeviceRow } from '../services/devices.js';
import { createPairingCode, redeemActivationCode } from '../services/codes.js';
import { commandMessage, completeCommand, takePendingCommands } from '../services/commands.js';

const deviceInfoSchema = z.object({
  flavor: z.enum(['pro', 'lite']).default('pro'),
  isTv: z.boolean().default(false),
  manufacturer: z.string().max(100).default(''),
  model: z.string().max(100).default(''),
  androidVersion: z.string().max(30).default(''),
  sdkInt: z.number().int().min(0).max(100).default(0),
  appVersionName: z.string().max(50).default(''),
  appVersionCode: z.number().int().min(0).default(0),
});

const statusSchema = z
  .object({
    appVersionName: z.string().max(50).optional(),
    appVersionCode: z.number().int().optional(),
    /** Šta se trenutno gleda, nivo launchera, memorija, mreža... (slobodna struktura, ograničena veličina) */
    status: z.record(z.string(), z.unknown()).optional(),
  })
  .refine((v) => JSON.stringify(v.status ?? {}).length < 8000, 'Status je prevelik');

export async function authenticateDevice(ctx: Ctx, header: string | undefined): Promise<DeviceRow> {
  const raw = (header ?? '').replace(/^(Device|Bearer)\s+/i, '');
  const [id, token] = raw.split('.');
  if (!id || !token || !/^[0-9a-f-]{36}$/i.test(id)) throw unauthorized('Uređaj nije registrovan', 'device_auth');
  const { rows } = await ctx.db.query<DeviceRow>(`SELECT * FROM devices WHERE id = $1`, [id]);
  const d = rows[0];
  if (!d || !safeEqual(d.token_hash, sha256(token))) throw unauthorized('Uređaj nije registrovan', 'device_auth');
  return d;
}

declare module 'fastify' {
  interface FastifyRequest {
    device?: DeviceRow;
  }
}

export function deviceRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    const auth = async (req: FastifyRequest) => {
      req.device = await authenticateDevice(ctx, req.headers.authorization);
    };

    const touch = (d: DeviceRow, ip: string) =>
      ctx.db.query(`UPDATE devices SET last_seen_at = now(), last_ip = $2 WHERE id = $1`, [d.id, ip]);

    app.post('/register', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
      const info = deviceInfoSchema.parse(req.body ?? {});
      const token = randomToken();
      const { rows } = await ctx.db.query<{ id: string }>(
        `INSERT INTO devices (token_hash, flavor, is_tv, manufacturer, model, android_version, sdk_int,
           app_version_name, app_version_code, name, last_ip, last_seen_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11, now()) RETURNING id`,
        [
          sha256(token),
          info.flavor,
          info.isTv,
          info.manufacturer,
          info.model,
          info.androidVersion,
          info.sdkInt,
          info.appVersionName,
          info.appVersionCode,
          `${info.manufacturer} ${info.model}`.trim().slice(0, 80),
          req.ip,
        ],
      );
      const id = rows[0]!.id;
      return { deviceId: id, token, auth: `${id}.${token}` };
    });

    app.register(async (secured) => {
      secured.addHook('preHandler', auth);

      secured.get('/config', async (req) => {
        await touch(req.device!, req.ip);
        return buildDeviceConfig(ctx, req.device!);
      });

      secured.post('/info', async (req) => {
        const info = deviceInfoSchema.parse(req.body ?? {});
        await ctx.db.query(
          `UPDATE devices SET is_tv=$2, manufacturer=$3, model=$4, android_version=$5, sdk_int=$6,
             app_version_name=$7, app_version_code=$8, flavor=$9 WHERE id=$1`,
          [
            req.device!.id,
            info.isTv,
            info.manufacturer,
            info.model,
            info.androidVersion,
            info.sdkInt,
            info.appVersionName,
            info.appVersionCode,
            info.flavor,
          ],
        );
        return { ok: true };
      });

      secured.post('/pairing-code', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
        return createPairingCode(ctx, req.device!.id);
      });

      secured.post('/activate', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req) => {
        const { code } = z.object({ code: z.string().max(20) }).parse(req.body ?? {});
        await ctx.db.tx((q) => redeemActivationCode(ctx, q, req.device!.id, code));
        const { rows } = await ctx.db.query<DeviceRow>(`SELECT * FROM devices WHERE id = $1`, [req.device!.id]);
        return buildDeviceConfig(ctx, rows[0]!);
      });

      secured.post('/heartbeat', async (req) => {
        const body = statusSchema.parse(req.body ?? {});
        const d = req.device!;
        await ctx.db.query(
          `UPDATE devices SET last_seen_at = now(), last_ip = $2,
             status = COALESCE($3::jsonb, status),
             app_version_name = COALESCE($4, app_version_name),
             app_version_code = COALESCE($5, app_version_code)
           WHERE id = $1`,
          [d.id, req.ip, body.status ? JSON.stringify(body.status) : null, body.appVersionName ?? null, body.appVersionCode ?? null],
        );
        const pending = await takePendingCommands(ctx, d.id);
        return { commands: pending.map((c) => commandMessage(c).command), online: ctx.hub.isOnline(d.id) };
      });

      secured.post('/commands/:id/result', async (req) => {
        const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
        const body = z.object({ ok: z.boolean(), result: z.unknown().optional() }).parse(req.body ?? {});
        await completeCommand(ctx, req.device!.id, id, body.ok, body.result);
        return { ok: true };
      });

      secured.post('/logs', async (req) => {
        const body = z
          .object({
            entries: z
              .array(
                z.object({
                  level: z.enum(['debug', 'info', 'warn', 'error', 'crash']).default('info'),
                  kind: z.string().max(40).default('log'),
                  message: z.string().max(10000),
                  data: z.unknown().optional(),
                }),
              )
              .max(100),
          })
          .parse(req.body ?? {});
        for (const e of body.entries) {
          await ctx.db.query(`INSERT INTO device_logs (device_id, level, kind, message, data) VALUES ($1,$2,$3,$4,$5)`, [
            req.device!.id,
            e.level,
            e.kind,
            e.message,
            e.data === undefined ? null : JSON.stringify(e.data).slice(0, 50000),
          ]);
        }
        // čuvamo najviše 500 zapisa po uređaju
        await ctx.db.query(
          `DELETE FROM device_logs WHERE device_id = $1 AND id NOT IN
             (SELECT id FROM device_logs WHERE device_id = $1 ORDER BY created_at DESC LIMIT 500)`,
          [req.device!.id],
        );
        return { ok: true };
      });

      secured.get('/update', async (req) => {
        const d = req.device!;
        const { channel } = z.object({ channel: z.enum(['stable', 'beta']).default('stable') }).parse(req.query ?? {});
        const channels = channel === 'beta' ? ['stable', 'beta'] : ['stable'];
        const { rows } = await ctx.db.query(
          `SELECT * FROM releases WHERE flavor = $1 AND channel = ANY($2::text[]) AND version_code > $3 AND min_sdk <= $4
           ORDER BY version_code DESC LIMIT 5`,
          [d.flavor, channels, d.app_version_code, d.sdk_int || 99],
        );
        const rel = rows.find((r) => inRollout(d.id, r.id, r.rollout_percent));
        if (!rel) return { available: false };
        return {
          available: true,
          release: {
            id: rel.id,
            versionCode: rel.version_code,
            versionName: rel.version_name,
            size: Number(rel.size),
            sha256: rel.sha256,
            changelog: rel.changelog,
            mandatory: rel.mandatory,
            url: `${ctx.config.publicUrl}/api/device/releases/${rel.id}/download`,
          },
        };
      });

      secured.get('/releases/:id/download', async (req, reply) => {
        const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
        const { rows } = await ctx.db.query(`SELECT * FROM releases WHERE id = $1`, [id]);
        const rel = rows[0];
        if (!rel) throw notFound();
        if (rel.flavor !== req.device!.flavor) throw forbidden();
        return sendApk(ctx, reply, rel);
      });
    });

    // WebSocket za komande uživo. Autorizacija preko zaglavlja ili ?auth=
    app.get('/ws', { websocket: true }, async (socket, req) => {
      let device: DeviceRow;
      try {
        const q = (req.query as Record<string, string>) ?? {};
        device = await authenticateDevice(ctx, req.headers.authorization ?? q.auth);
      } catch {
        socket.close(4401, 'unauthorized');
        return;
      }
      ctx.hub.add(device.id, socket);
      await touch(device, req.ip);
      socket.send(JSON.stringify({ type: 'hello', serverTime: new Date().toISOString() }));
      for (const c of await takePendingCommands(ctx, device.id)) socket.send(JSON.stringify(commandMessage(c)));

      let alive = true;
      socket.on('pong', () => (alive = true));
      const pinger = setInterval(() => {
        if (!alive) return socket.terminate();
        alive = false;
        socket.ping();
      }, 30_000);

      socket.on('message', async (raw: Buffer) => {
        try {
          const msg = JSON.parse(raw.toString());
          if (msg?.type === 'status' && msg.status && typeof msg.status === 'object') {
            const json = JSON.stringify(msg.status);
            if (json.length < 8000) {
              await ctx.db.query(`UPDATE devices SET status = $2::jsonb, last_seen_at = now() WHERE id = $1`, [device.id, json]);
            }
          } else if (msg?.type === 'command_result' && typeof msg.id === 'string') {
            await completeCommand(ctx, device.id, msg.id, !!msg.ok, msg.result);
          } else if (msg?.type === 'ping') {
            socket.send(JSON.stringify({ type: 'pong' }));
          }
        } catch (e) {
          ctx.log.debug({ err: e }, 'bad ws message');
        }
      });
      socket.on('close', () => {
        clearInterval(pinger);
        ctx.hub.remove(device.id, socket);
        ctx.db.query(`UPDATE devices SET last_seen_at = now() WHERE id = $1`, [device.id]).catch(() => undefined);
      });
    });
  };
}

/** Deterministički raspoređuje uređaje u postepeno puštanje nove verzije (npr. 10% uređaja). */
export function inRollout(deviceId: string, releaseId: string, percent: number): boolean {
  if (percent >= 100) return true;
  if (percent <= 0) return false;
  const h = crypto.createHash('sha256').update(`${deviceId}:${releaseId}`).digest();
  return h.readUInt16BE(0) % 100 < percent;
}

export function releasePath(ctx: Ctx, fileName: string) {
  return path.join(ctx.config.dataDir, 'releases', path.basename(fileName));
}

export function sendApk(ctx: Ctx, reply: any, rel: any) {
  const p = releasePath(ctx, rel.file_name);
  if (!fs.existsSync(p)) throw notFound('Fajl nije pronađen');
  reply.header('content-type', 'application/vnd.android.package-archive');
  reply.header('content-length', String(rel.size));
  const safeName = `${ctx.config.appName.replace(/[^A-Za-z0-9]+/g, '')}-${rel.flavor}-${rel.version_name}.apk`.replace(/[^A-Za-z0-9._-]/g, '');
  reply.header('content-disposition', `attachment; filename="${safeName}"`);
  return reply.send(fs.createReadStream(p));
}
