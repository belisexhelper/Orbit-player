import type { FastifyInstance, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { signToken, verifyToken } from '../lib/crypto.js';
import { badRequest, notFound, unauthorized } from '../lib/errors.js';
import { playlistInputSchema, secretFromInput, testPlaylist } from '../lib/playlist.js';
import type { Ctx } from '../services/context.js';
import { findDeviceByPairingCode } from '../services/codes.js';
import { notifyConfigChanged } from '../services/devices.js';
import { insertPlaylist, mergeKeepPassword, publicPlaylist, updatePlaylist, type PlaylistRow } from '../services/playlists.js';
import { sendApk } from './device.js';

const LINK_SESSION_TTL = 30 * 60;

export function publicRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    app.get('/info', async () => {
      const { rows } = await ctx.db.query(
        `SELECT DISTINCT ON (flavor) flavor, version_name, version_code, size, created_at, changelog
         FROM releases WHERE channel = 'stable' ORDER BY flavor, version_code DESC`,
      );
      const downloads: Record<string, unknown> = {};
      for (const r of rows) {
        downloads[r.flavor] = {
          versionName: r.version_name,
          versionCode: r.version_code,
          size: Number(r.size),
          date: r.created_at,
          changelog: r.changelog,
          url: `/api/public/download/${r.flavor}`,
        };
      }
      const s = await ctx.db.query(`SELECT value FROM settings WHERE key = 'site'`);
      return { appName: ctx.config.appName, downloads, site: s.rows[0]?.value ?? {} };
    });

    app.get('/download/:flavor', async (req, reply) => {
      const { flavor } = z.object({ flavor: z.enum(['pro', 'lite']) }).parse(req.params);
      const { rows } = await ctx.db.query(
        `SELECT * FROM releases WHERE flavor = $1 AND channel = 'stable' ORDER BY version_code DESC LIMIT 1`,
        [flavor],
      );
      if (!rows[0]) throw notFound('Aplikacija još nije objavljena');
      return sendApk(ctx, reply, rows[0]);
    });

    // ---------- Uparivanje (smer A): korisnik unosi kod sa TV-a ----------

    app.post('/link/start', { config: { rateLimit: { max: 8, timeWindow: '1 minute' } } }, async (req) => {
      const { code } = z.object({ code: z.string().max(20) }).parse(req.body ?? {});
      const deviceId = await findDeviceByPairingCode(ctx, code);
      const { rows } = await ctx.db.query(`SELECT id, name, model, manufacturer, blocked FROM devices WHERE id = $1`, [deviceId]);
      const d = rows[0];
      if (!d) throw notFound('Uređaj nije pronađen');
      if (d.blocked) throw badRequest('Ovaj uređaj je blokiran. Obratite se podršci.', 'device_blocked');
      const sessionToken = signToken(ctx.config.appSecret, { did: deviceId, p: 'link' }, LINK_SESSION_TTL);
      return {
        sessionToken,
        expiresInSec: LINK_SESSION_TTL,
        device: { name: d.name || `${d.manufacturer} ${d.model}`.trim() },
        playlists: await listOwn(ctx, deviceId),
      };
    });

    app.register(async (s) => {
      s.addHook('preHandler', async (req: FastifyRequest) => {
        const token = (req.headers.authorization ?? '').replace(/^Bearer\s+/i, '');
        const payload = verifyToken<{ did: string; p: string }>(ctx.config.appSecret, token);
        if (!payload || payload.p !== 'link') throw unauthorized('Sesija je istekla. Unesite kod ponovo.', 'link_session');
        (req as any).linkDeviceId = payload.did;
      });

      s.get('/link/playlists', async (req) => ({ playlists: await listOwn(ctx, (req as any).linkDeviceId) }));

      s.post('/link/test', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
        const input = playlistInputSchema.parse((req.body as any)?.playlist);
        return testPlaylist(input.type, secretFromInput(input), ctx.config.allowPrivateUrls);
      });

      s.post('/link/playlists', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (req) => {
        const deviceId = (req as any).linkDeviceId as string;
        const body = z.object({ playlist: z.unknown(), skipTest: z.boolean().optional() }).parse(req.body ?? {});
        const input = playlistInputSchema.parse(body.playlist);
        const count = await ctx.db.query<{ n: number }>(`SELECT count(*)::int AS n FROM playlists WHERE device_id = $1`, [deviceId]);
        if ((count.rows[0]?.n ?? 0) >= 10) throw badRequest('Najviše 10 lista po uređaju', 'too_many');
        let test = null;
        if (!body.skipTest) {
          test = await testPlaylist(input.type, secretFromInput(input), ctx.config.allowPrivateUrls);
          if (!test.ok) return { saved: false, test };
        }
        const row = await insertPlaylist(ctx, ctx.db, { deviceId }, input, 'web');
        await notifyConfigChanged(ctx, { deviceIds: [deviceId] });
        return { saved: true, test, playlist: publicPlaylist(ctx, row) };
      });

      s.put('/link/playlists/:id', async (req) => {
        const deviceId = (req as any).linkDeviceId as string;
        const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
        const row = await ownRow(ctx, deviceId, id);
        const input = mergeKeepPassword(ctx, row, playlistInputSchema.parse((req.body as any)?.playlist));
        const updated = await updatePlaylist(ctx, ctx.db, id, input);
        await notifyConfigChanged(ctx, { deviceIds: [deviceId] });
        return { playlist: publicPlaylist(ctx, updated!) };
      });

      s.delete('/link/playlists/:id', async (req) => {
        const deviceId = (req as any).linkDeviceId as string;
        const { id } = z.object({ id: z.string().uuid() }).parse(req.params);
        await ownRow(ctx, deviceId, id);
        await ctx.db.query(`DELETE FROM playlists WHERE id = $1`, [id]);
        await notifyConfigChanged(ctx, { deviceIds: [deviceId] });
        return { ok: true };
      });
    });
  };
}

async function listOwn(ctx: Ctx, deviceId: string) {
  const { rows } = await ctx.db.query<PlaylistRow>(`SELECT * FROM playlists WHERE device_id = $1 ORDER BY sort, created_at`, [deviceId]);
  return rows.map((r) => publicPlaylist(ctx, r));
}

async function ownRow(ctx: Ctx, deviceId: string, id: string) {
  const { rows } = await ctx.db.query<PlaylistRow>(`SELECT * FROM playlists WHERE id = $1 AND device_id = $2`, [id, deviceId]);
  if (!rows[0]) throw notFound('Lista nije pronađena');
  return rows[0];
}
