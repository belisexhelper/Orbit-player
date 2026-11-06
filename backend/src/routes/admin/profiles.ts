import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { badRequest, notFound } from '../../lib/errors.js';
import { playlistInputSchema, secretFromInput, testPlaylist } from '../../lib/playlist.js';
import { sanitizeSettings } from '../../lib/settings.js';
import { audit } from '../../services/audit.js';
import { createActivationCode } from '../../services/codes.js';
import type { Ctx } from '../../services/context.js';
import { deviceSummary, notifyConfigChanged } from '../../services/devices.js';
import {
  decryptSecret,
  insertPlaylist,
  mergeKeepPassword,
  publicPlaylist,
  updatePlaylist,
  type PlaylistRow,
} from '../../services/playlists.js';
import { requireRole } from './auth.js';
import { getDeviceOr404, getLauncherOr404, getProfileOr404, isReseller } from './scope.js';

const uuid = z.string().uuid();

const profileSchema = z.object({
  name: z.string().trim().min(1, 'Unesite naziv').max(120),
  note: z.string().max(2000).default(''),
  contact: z.string().max(200).default(''),
  expiresAt: z.string().datetime({ offset: true }).nullable().optional(),
  maxDevices: z.number().int().min(1).max(100).default(1),
  launcherProfileId: uuid.nullable().optional(),
  settings: z.record(z.string(), z.unknown()).optional(),
});

function profileOut(p: any) {
  return {
    id: p.id,
    name: p.name,
    note: p.note,
    contact: p.contact,
    expiresAt: p.expires_at,
    expired: !!(p.expires_at && new Date(p.expires_at).getTime() < Date.now()),
    maxDevices: p.max_devices,
    launcherProfileId: p.launcher_profile_id,
    settings: p.settings,
    createdAt: p.created_at,
    updatedAt: p.updated_at,
    deviceCount: p.device_count ?? undefined,
    playlistCount: p.playlist_count ?? undefined,
  };
}

export function profileRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    app.get('/profiles', async (req) => {
      const q = z
        .object({ q: z.string().max(100).optional(), filter: z.enum(['all', 'expired', 'expiring']).default('all'), limit: z.coerce.number().int().min(1).max(500).default(100), offset: z.coerce.number().int().min(0).default(0) })
        .parse(req.query ?? {});
      const where: string[] = [];
      const params: unknown[] = [];
      if (isReseller(req)) {
        params.push(req.admin!.id);
        where.push(`p.owner_admin_id = $${params.length}`);
      }
      if (q.q) {
        params.push(`%${q.q}%`);
        where.push(`(p.name ILIKE $${params.length} OR p.note ILIKE $${params.length} OR p.contact ILIKE $${params.length})`);
      }
      if (q.filter === 'expired') where.push(`p.expires_at < now()`);
      if (q.filter === 'expiring') where.push(`p.expires_at BETWEEN now() AND now() + interval '7 days'`);
      const w = where.length ? `WHERE ${where.join(' AND ')}` : '';
      params.push(q.limit, q.offset);
      const { rows } = await ctx.db.query(
        `SELECT p.*,
           (SELECT count(*)::int FROM devices d WHERE d.profile_id = p.id) AS device_count,
           (SELECT count(*)::int FROM playlists pl WHERE pl.profile_id = p.id) AS playlist_count,
           count(*) OVER()::int AS total
         FROM profiles p ${w} ORDER BY p.created_at DESC LIMIT $${params.length - 1} OFFSET $${params.length}`,
        params,
      );
      return { total: rows[0]?.total ?? 0, items: rows.map(profileOut) };
    });

    app.post('/profiles', async (req) => {
      requireRole(req, 'super', 'reseller');
      const body = profileSchema
        .extend({ playlists: z.array(z.unknown()).max(10).optional() })
        .parse(req.body ?? {});
      if (body.launcherProfileId) await getLauncherOr404(ctx, req, body.launcherProfileId);
      const playlists = (body.playlists ?? []).map((p) => playlistInputSchema.parse(p));
      const profile = await ctx.db.tx(async (q) => {
        const { rows } = await q.query(
          `INSERT INTO profiles (name, note, contact, expires_at, max_devices, launcher_profile_id, settings, owner_admin_id)
           VALUES ($1,$2,$3,$4,$5,$6,$7,$8) RETURNING *`,
          [
            body.name,
            body.note,
            body.contact,
            body.expiresAt ?? null,
            body.maxDevices,
            body.launcherProfileId ?? null,
            JSON.stringify(sanitizeSettings(body.settings ?? {})),
            req.admin!.id,
          ],
        );
        for (const pl of playlists) await insertPlaylist(ctx, q, { profileId: rows[0].id }, pl, 'admin');
        return rows[0];
      });
      await audit(ctx, req.admin!, 'profile_created', profile.id, { name: body.name }, req.ip);
      return { profile: profileOut(profile) };
    });

    app.get('/profiles/:id', async (req) => {
      const { id } = z.object({ id: uuid }).parse(req.params);
      const p = await getProfileOr404(ctx, req, id);
      const [pls, devs, codes] = await Promise.all([
        ctx.db.query<PlaylistRow>(`SELECT * FROM playlists WHERE profile_id = $1 ORDER BY sort, created_at`, [id]),
        ctx.db.query(`SELECT * FROM devices WHERE profile_id = $1 ORDER BY last_seen_at DESC NULLS LAST`, [id]),
        ctx.db.query(
          `SELECT code_hash, code_hint, expires_at, max_uses, uses, created_at FROM activation_codes
           WHERE profile_id = $1 AND expires_at > now() AND uses < max_uses ORDER BY created_at DESC`,
          [id],
        ),
      ]);
      return {
        profile: profileOut(p),
        playlists: pls.rows.map((r) => publicPlaylist(ctx, r)),
        devices: devs.rows.map((d) => deviceSummary(ctx, d)),
        activationCodes: codes.rows.map((c) => ({
          id: c.code_hash,
          hint: c.code_hint,
          expiresAt: c.expires_at,
          maxUses: c.max_uses,
          uses: c.uses,
          createdAt: c.created_at,
        })),
      };
    });

    app.patch('/profiles/:id', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getProfileOr404(ctx, req, id);
      const body = profileSchema.partial().parse(req.body ?? {});
      if (body.launcherProfileId) await getLauncherOr404(ctx, req, body.launcherProfileId);
      const sets: string[] = [];
      const params: unknown[] = [id];
      const set = (col: string, v: unknown) => {
        params.push(v);
        sets.push(`${col} = $${params.length}`);
      };
      if (body.name !== undefined) set('name', body.name);
      if (body.note !== undefined) set('note', body.note);
      if (body.contact !== undefined) set('contact', body.contact);
      if (body.expiresAt !== undefined) set('expires_at', body.expiresAt);
      if (body.maxDevices !== undefined) set('max_devices', body.maxDevices);
      if (body.launcherProfileId !== undefined) set('launcher_profile_id', body.launcherProfileId);
      if (body.settings !== undefined) set('settings', JSON.stringify(sanitizeSettings(body.settings)));
      if (!sets.length) throw badRequest('Nema izmena');
      const { rows } = await ctx.db.query(`UPDATE profiles SET ${sets.join(', ')}, updated_at = now() WHERE id = $1 RETURNING *`, params);
      await notifyConfigChanged(ctx, { profileId: id });
      await audit(ctx, req.admin!, 'profile_updated', id, body as any, req.ip);
      return { profile: profileOut(rows[0]) };
    });

    app.delete('/profiles/:id', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const p = await getProfileOr404(ctx, req, id);
      const devs = await ctx.db.query<{ id: string }>(`SELECT id FROM devices WHERE profile_id = $1`, [id]);
      await ctx.db.query(`DELETE FROM profiles WHERE id = $1`, [id]);
      await notifyConfigChanged(ctx, { deviceIds: devs.rows.map((d) => d.id) });
      await audit(ctx, req.admin!, 'profile_deleted', id, { name: p.name }, req.ip);
      return { ok: true };
    });

    // ---------- Kodovi za aktivaciju (smer B) ----------

    app.post('/profiles/:id/activation-codes', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getProfileOr404(ctx, req, id);
      const body = z
        .object({ ttlHours: z.number().int().min(1).max(24 * 30).default(72), maxUses: z.number().int().min(1).max(100).default(1) })
        .parse(req.body ?? {});
      const code = await createActivationCode(ctx, id, { ...body, adminId: req.admin!.id });
      await audit(ctx, req.admin!, 'activation_code_created', id, { maxUses: body.maxUses, ttlHours: body.ttlHours }, req.ip);
      return code;
    });

    app.delete('/activation-codes/:id', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: z.string().regex(/^[0-9a-f]{64}$/) }).parse(req.params);
      const { rows } = await ctx.db.query(`SELECT profile_id FROM activation_codes WHERE code_hash = $1`, [id]);
      if (!rows[0]) throw notFound('Kod nije pronađen');
      await getProfileOr404(ctx, req, rows[0].profile_id);
      await ctx.db.query(`DELETE FROM activation_codes WHERE code_hash = $1`, [id]);
      return { ok: true };
    });

    // ---------- Liste (playliste) profila i uređaja ----------

    app.post('/playlists/test', { config: { rateLimit: { max: 30, timeWindow: '1 minute' } } }, async (req) => {
      const body = z.object({ playlist: z.unknown(), existingId: uuid.optional() }).parse(req.body ?? {});
      let input = playlistInputSchema.parse(body.playlist);
      if (body.existingId) {
        const row = await getPlaylistOr404(ctx, req, body.existingId);
        input = mergeKeepPassword(ctx, row, input);
      }
      return testPlaylist(input.type, secretFromInput(input), ctx.config.allowPrivateUrls);
    });

    app.post('/profiles/:id/playlists', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getProfileOr404(ctx, req, id);
      const input = playlistInputSchema.parse((req.body as any)?.playlist);
      const row = await insertPlaylist(ctx, ctx.db, { profileId: id }, input, 'admin');
      await notifyConfigChanged(ctx, { profileId: id });
      await audit(ctx, req.admin!, 'playlist_added', id, { name: input.name, type: input.type }, req.ip);
      return { playlist: publicPlaylist(ctx, row) };
    });

    app.post('/devices/:id/playlists', async (req) => {
      requireRole(req, 'super', 'reseller', 'support');
      const { id } = z.object({ id: uuid }).parse(req.params);
      await getDeviceOr404(ctx, req, id);
      const input = playlistInputSchema.parse((req.body as any)?.playlist);
      const row = await insertPlaylist(ctx, ctx.db, { deviceId: id }, input, 'admin');
      await notifyConfigChanged(ctx, { deviceIds: [id] });
      await audit(ctx, req.admin!, 'playlist_added', id, { name: input.name, type: input.type }, req.ip);
      return { playlist: publicPlaylist(ctx, row) };
    });

    app.get('/playlists/:id/reveal', async (req) => {
      requireRole(req, 'super', 'reseller');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const row = await getPlaylistOr404(ctx, req, id);
      await audit(ctx, req.admin!, 'playlist_revealed', id, null, req.ip);
      return { secret: decryptSecret(ctx, row) };
    });

    app.put('/playlists/:id', async (req) => {
      requireRole(req, 'super', 'reseller', 'support');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const row = await getPlaylistOr404(ctx, req, id);
      const input = mergeKeepPassword(ctx, row, playlistInputSchema.parse((req.body as any)?.playlist));
      const updated = await updatePlaylist(ctx, ctx.db, id, input);
      await notifyPlaylistOwner(ctx, row);
      await audit(ctx, req.admin!, 'playlist_updated', id, { name: input.name }, req.ip);
      return { playlist: publicPlaylist(ctx, updated!) };
    });

    app.delete('/playlists/:id', async (req) => {
      requireRole(req, 'super', 'reseller', 'support');
      const { id } = z.object({ id: uuid }).parse(req.params);
      const row = await getPlaylistOr404(ctx, req, id);
      await ctx.db.query(`DELETE FROM playlists WHERE id = $1`, [id]);
      await notifyPlaylistOwner(ctx, row);
      await audit(ctx, req.admin!, 'playlist_deleted', id, { name: row.name }, req.ip);
      return { ok: true };
    });

    app.post('/playlists/reorder', async (req) => {
      const body = z.object({ ids: z.array(uuid).min(1).max(50) }).parse(req.body ?? {});
      const rows: PlaylistRow[] = [];
      for (const id of body.ids) rows.push(await getPlaylistOr404(ctx, req, id));
      for (let i = 0; i < body.ids.length; i++) await ctx.db.query(`UPDATE playlists SET sort = $2 WHERE id = $1`, [body.ids[i], i]);
      if (rows[0]) await notifyPlaylistOwner(ctx, rows[0]);
      return { ok: true };
    });
  };
}

async function getPlaylistOr404(ctx: Ctx, req: any, id: string): Promise<PlaylistRow> {
  const { rows } = await ctx.db.query<PlaylistRow>(`SELECT * FROM playlists WHERE id = $1`, [id]);
  const row = rows[0];
  if (!row) throw notFound('Lista nije pronađena');
  if (row.profile_id) await getProfileOr404(ctx, req, row.profile_id);
  if (row.device_id) await getDeviceOr404(ctx, req, row.device_id);
  return row;
}

async function notifyPlaylistOwner(ctx: Ctx, row: PlaylistRow) {
  if (row.profile_id) await notifyConfigChanged(ctx, { profileId: row.profile_id });
  if (row.device_id) await notifyConfigChanged(ctx, { deviceIds: [row.device_id] });
}
