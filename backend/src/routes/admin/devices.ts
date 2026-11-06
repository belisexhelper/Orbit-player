import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { badRequest } from '../../lib/errors.js';
import { sanitizeSettings } from '../../lib/settings.js';
import { audit } from '../../services/audit.js';
import { findDeviceByPairingCode } from '../../services/codes.js';
import { COMMAND_TYPES, createCommand } from '../../services/commands.js';
import type { Ctx } from '../../services/context.js';
import { deviceSummary, notifyConfigChanged } from '../../services/devices.js';
import { publicPlaylist, type PlaylistRow } from '../../services/playlists.js';
import { requireRole } from './auth.js';
import { getDeviceOr404, getLauncherOr404, getProfileOr404, isReseller } from './scope.js';

const uuid = z.string().uuid();
const commandSchema = z.object({
  type: z.enum(COMMAND_TYPES),
  payload: z.record(z.string(), z.unknown()).default({}),
});

/** Komande koje podrška ne sme da šalje */
const DESTRUCTIVE: string[] = ['unpair', 'reboot', 'exit_kiosk'];

export function deviceRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    app.get('/devices', async (req) => {
      const q = z
        .object({
          q: z.string().max(100).optional(),
          status: z.enum(['all', 'online', 'offline', 'blocked', 'unassigned']).default('all'),
          profileId: uuid.optional(),
          limit: z.coerce.number().int().min(1).max(500).default(100),
          offset: z.coerce.number().int().min(0).default(0),
        })
        .parse(req.query ?? {});
      const where: string[] = [];
      const params: unknown[] = [];
      const add = (sql: string, v?: unknown) => {
        if (v !== undefined) params.push(v);
        where.push(sql.replace('$?', `$${params.length}`));
      };
      if (isReseller(req)) add(`p.owner_admin_id = $?`, req.admin!.id);
      if (q.q) add(`(d.name ILIKE $? OR d.model ILIKE $? OR d.manufacturer ILIKE $? OR d.last_ip ILIKE $? OR d.id::text ILIKE $? OR p.name ILIKE $?)`.replaceAll('$?', `$${params.length + 1}`), `%${q.q}%`);
      if (q.profileId) add(`d.profile_id = $?`, q.profileId);
      if (q.status === 'blocked') add(`d.blocked`);
      if (q.status === 'unassigned') add(`d.profile_id IS NULL`);
      const online = ctx.hub.onlineIds();
      if (q.status === 'online') add(`d.id = ANY($?::uuid[])`, online);
      if (q.status === 'offline') add(`NOT (d.id = ANY($?::uuid[]))`, online);
      const w = where.length ? `WHERE ${where.join(' AND ')}` : '';
      params.push(q.limit, q.offset);
      const { rows } = await ctx.db.query(
        `SELECT d.*, p.name AS profile_name, l.name AS launcher_name, count(*) OVER()::int AS total
         FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id
         LEFT JOIN launcher_profiles l ON l.id = COALESCE(d.launcher_profile_id, p.launcher_profile_id)
         ${w} ORDER BY d.last_seen_at DESC NULLS LAST LIMIT $${params.length - 1} OFFSET $${params.length}`,
        params,
      );
      return { total: rows[0]?.total ?? 0, items: rows.map((r) => deviceSummary(ctx, r)) };
    });

    app.get('/devices/:id', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      const d = await getDeviceOr404(ctx, req, id);
      const [profile, launcher, playlists, commands, logs] = await Promise.all([
        d.profile_id ? ctx.db.query(`SELECT id, name, expires_at FROM profiles WHERE id = $1`, [d.profile_id]) : { rows: [] },
        d.launcher_profile_id ? ctx.db.query(`SELECT id, name FROM launcher_profiles WHERE id = $1`, [d.launcher_profile_id]) : { rows: [] },
        ctx.db.query<PlaylistRow>(`SELECT * FROM playlists WHERE device_id = $1 ORDER BY sort, created_at`, [id]),
        ctx.db.query(`SELECT * FROM commands WHERE device_id = $1 ORDER BY created_at DESC LIMIT 30`, [id]),
        ctx.db.query(`SELECT id, level, kind, message, data, created_at FROM device_logs WHERE device_id = $1 ORDER BY created_at DESC LIMIT 100`, [id]),
      ]);
      return {
        device: deviceSummary(ctx, { ...d, profile_name: profile.rows[0]?.name, launcher_name: launcher.rows[0]?.name }),
        profile: profile.rows[0] ? { id: profile.rows[0].id, name: profile.rows[0].name, expiresAt: profile.rows[0].expires_at } : null,
        playlists: playlists.rows.map((r) => publicPlaylist(ctx, r)),
        commands: commands.rows.map((c) => ({
          id: c.id,
          type: c.type,
          payload: c.payload,
          status: c.status,
          result: c.result,
          createdAt: c.created_at,
          doneAt: c.done_at,
        })),
        logs: logs.rows.map((l) => ({ id: l.id, level: l.level, kind: l.kind, message: l.message, data: l.data, createdAt: l.created_at })),
      };
    });

    app.patch('/devices/:id', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      const d = await getDeviceOr404(ctx, req, id);
      const body = z
        .object({
          name: z.string().trim().max(80).optional(),
          profileId: uuid.nullable().optional(),
          launcherProfileId: uuid.nullable().optional(),
          settings: z.record(z.string(), z.unknown()).optional(),
          blocked: z.boolean().optional(),
        })
        .parse(req.body ?? {});
      if (req.admin!.role === 'support' && (body.profileId !== undefined || body.blocked !== undefined)) {
        requireRole(req, 'super', 'reseller');
      }
      if (body.profileId) await getProfileOr404(ctx, req, body.profileId);
      if (body.launcherProfileId) await getLauncherOr404(ctx, req, body.launcherProfileId);
      if (isReseller(req) && body.profileId === null) throw badRequest('Reseler ne može da odvoji uređaj od svih profila');
      const sets: string[] = [];
      const params: unknown[] = [id];
      const set = (col: string, v: unknown) => {
        params.push(v);
        sets.push(`${col} = $${params.length}`);
      };
      if (body.name !== undefined) set('name', body.name);
      if (body.profileId !== undefined) set('profile_id', body.profileId);
      if (body.launcherProfileId !== undefined) set('launcher_profile_id', body.launcherProfileId);
      if (body.settings !== undefined) set('settings', JSON.stringify(sanitizeSettings(body.settings)));
      if (body.blocked !== undefined) set('blocked', body.blocked);
      if (!sets.length) throw badRequest('Nema izmena');
      await ctx.db.query(`UPDATE devices SET ${sets.join(', ')} WHERE id = $1`, params);
      await notifyConfigChanged(ctx, { deviceIds: [id] });
      await audit(ctx, req.admin!, 'device_updated', id, { ...body, previousName: d.name } as any, req.ip);
      return { ok: true };
    });

    app.delete('/devices/:id', async (req) => {
      requireRole(req, 'super');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const d = await getDeviceOr404(ctx, req, id);
      ctx.hub.send(id, { type: 'command', command: { id: '00000000-0000-0000-0000-000000000000', type: 'unpair', payload: {} } });
      ctx.hub.disconnect(id);
      await ctx.db.query(`DELETE FROM devices WHERE id = $1`, [id]);
      await audit(ctx, req.admin!, 'device_deleted', id, { name: d.name, model: d.model }, req.ip);
      return { ok: true };
    });

    app.post('/devices/:id/commands', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getDeviceOr404(ctx, req, id);
      const body = commandSchema.parse(req.body ?? {});
      if (DESTRUCTIVE.includes(body.type)) requireRole(req, 'super', 'reseller');
      const cmd = await createCommand(ctx, id, body.type, body.payload, req.admin!.id);
      await audit(ctx, req.admin!, 'command_sent', id, { type: body.type }, req.ip);
      return { command: { id: cmd.id, status: cmd.status, type: cmd.type } };
    });

    /** Ista komanda za više uređaja: izabrani, ceo profil ili svi (samo super admin). */
    app.post('/commands/bulk', async (req) => {
      const body = commandSchema
        .extend({
          deviceIds: z.array(uuid).max(5000).optional(),
          profileId: uuid.optional(),
          all: z.boolean().optional(),
        })
        .parse(req.body ?? {});
      if (DESTRUCTIVE.includes(body.type)) requireRole(req, 'super', 'reseller');
      let ids: string[] = [];
      if (body.all) {
        requireRole(req, 'super');
        const { rows } = await ctx.db.query<{ id: string }>(
          `SELECT id FROM devices WHERE last_seen_at > now() - interval '30 days' AND NOT blocked`,
        );
        ids = rows.map((r) => r.id);
      } else if (body.profileId) {
        await getProfileOr404(ctx, req, body.profileId);
        const { rows } = await ctx.db.query<{ id: string }>(`SELECT id FROM devices WHERE profile_id = $1`, [body.profileId]);
        ids = rows.map((r) => r.id);
      } else if (body.deviceIds?.length) {
        for (const id of body.deviceIds) await getDeviceOr404(ctx, req, id);
        ids = body.deviceIds;
      } else {
        throw badRequest('Izaberite uređaje');
      }
      let sent = 0;
      for (const id of ids) {
        const c = await createCommand(ctx, id, body.type, body.payload, req.admin!.id);
        if (c.status === 'sent') sent++;
      }
      await audit(ctx, req.admin!, 'bulk_command', body.all ? 'all' : body.profileId ?? 'devices', { type: body.type, count: ids.length }, req.ip);
      return { total: ids.length, deliveredNow: sent };
    });

    /**
     * Smer B, varijanta: korisnik pročita kod sa TV ekrana, a admin ga ovde unese
     * i dodeli uređaj profilu — korisnik ne mora ništa da kuca.
     */
    app.post('/devices/claim', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
      requireRole(req, 'super', 'reseller');
      const body = z
        .object({ code: z.string().max(20), profileId: uuid, launcherProfileId: uuid.nullable().optional(), name: z.string().max(80).optional() })
        .parse(req.body ?? {});
      const profile = await getProfileOr404(ctx, req, body.profileId);
      if (body.launcherProfileId) await getLauncherOr404(ctx, req, body.launcherProfileId);
      const deviceId = await findDeviceByPairingCode(ctx, body.code);
      const cnt = await ctx.db.query<{ n: number }>(`SELECT count(*)::int AS n FROM devices WHERE profile_id = $1 AND id <> $2`, [
        profile.id,
        deviceId,
      ]);
      if ((cnt.rows[0]?.n ?? 0) >= profile.max_devices) {
        throw badRequest(`Profil već ima najveći dozvoljen broj uređaja (${profile.max_devices}).`, 'max_devices');
      }
      await ctx.db.query(
        `UPDATE devices SET profile_id = $2,
           launcher_profile_id = COALESCE($3, launcher_profile_id),
           name = CASE WHEN $4::text IS NULL OR $4::text = '' THEN name ELSE $4::text END
         WHERE id = $1`,
        [deviceId, profile.id, body.launcherProfileId ?? null, body.name ?? null],
      );
      await ctx.db.query(`DELETE FROM pairing_codes WHERE device_id = $1`, [deviceId]);
      await notifyConfigChanged(ctx, { deviceIds: [deviceId] });
      await audit(ctx, req.admin!, 'device_claimed', deviceId, { profileId: profile.id }, req.ip);
      return { deviceId };
    });

    app.get('/devices/:id/logs', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getDeviceOr404(ctx, req, id);
      const q = z.object({ kind: z.string().max(40).optional() }).parse(req.query ?? {});
      const { rows } = await ctx.db.query(
        `SELECT id, level, kind, message, data, created_at FROM device_logs
         WHERE device_id = $1 AND ($2::text IS NULL OR kind = $2) ORDER BY created_at DESC LIMIT 300`,
        [id, q.kind ?? null],
      );
      return { items: rows.map((l) => ({ id: l.id, level: l.level, kind: l.kind, message: l.message, data: l.data, createdAt: l.created_at })) };
    });
  };
}
