import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import {
  hashPassword,
  newTotpSecret,
  signToken,
  verifyPassword,
  verifyToken,
  verifyTotp,
} from '../../lib/crypto.js';
import { badRequest, forbidden, unauthorized } from '../../lib/errors.js';
import { audit, type AdminIdentity } from '../../services/audit.js';
import type { Ctx } from '../../services/context.js';

export const SESSION_COOKIE = 'orbit_admin';
const SESSION_TTL = 12 * 3600;

declare module 'fastify' {
  interface FastifyRequest {
    admin?: AdminIdentity;
  }
}

export function requireRole(req: FastifyRequest, ...roles: AdminIdentity['role'][]) {
  if (!req.admin) throw unauthorized();
  if (!roles.includes(req.admin.role)) throw forbidden();
}

export async function loadAdmin(ctx: Ctx, req: FastifyRequest): Promise<AdminIdentity> {
  const payload = verifyToken<{ aid: string; pv: string }>(ctx.config.appSecret, req.cookies[SESSION_COOKIE]);
  if (!payload) throw unauthorized();
  const { rows } = await ctx.db.query(
    `SELECT id, username, role, disabled, password_hash FROM admins WHERE id = $1`,
    [payload.aid],
  );
  const a = rows[0];
  // pv = deo heša lozinke: promena lozinke poništava sve stare sesije
  if (!a || a.disabled || a.password_hash.slice(-12) !== payload.pv) throw unauthorized();
  return { id: a.id, username: a.username, role: a.role };
}

function setSession(ctx: Ctx, reply: FastifyReply, adminId: string, passwordHash: string) {
  const token = signToken(ctx.config.appSecret, { aid: adminId, pv: passwordHash.slice(-12) }, SESSION_TTL);
  reply.setCookie(SESSION_COOKIE, token, {
    path: '/',
    httpOnly: true,
    sameSite: 'strict',
    secure: ctx.config.cookieSecure,
    maxAge: SESSION_TTL,
  });
}

export function authRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    app.post('/login', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
      const body = z
        .object({ username: z.string().max(100), password: z.string().max(200), totp: z.string().max(10).optional() })
        .parse(req.body ?? {});
      const { rows } = await ctx.db.query(`SELECT * FROM admins WHERE username = $1`, [body.username.trim().toLowerCase()]);
      const a = rows[0];
      const fail = async () => {
        if (a) {
          await ctx.db.query(
            `UPDATE admins SET failed_logins = failed_logins + 1,
               locked_until = CASE WHEN failed_logins + 1 >= 10 THEN now() + interval '15 minutes' ELSE locked_until END
             WHERE id = $1`,
            [a.id],
          );
        }
        await audit(ctx, null, 'login_failed', body.username, null, req.ip);
        throw unauthorized('Pogrešno korisničko ime ili lozinka', 'bad_credentials');
      };
      if (!a || a.disabled) return fail();
      if (a.locked_until && new Date(a.locked_until).getTime() > Date.now()) {
        throw forbidden('Nalog je privremeno zaključan zbog previše pogrešnih pokušaja. Pokušajte za 15 minuta.');
      }
      if (!(await verifyPassword(body.password, a.password_hash))) return fail();
      if (a.totp_secret) {
        if (!body.totp) throw unauthorized('Unesite kod iz aplikacije za autentifikaciju', 'totp_required');
        if (!verifyTotp(a.totp_secret, body.totp)) return fail();
      }
      await ctx.db.query(`UPDATE admins SET failed_logins = 0, locked_until = NULL, last_login_at = now() WHERE id = $1`, [a.id]);
      setSession(ctx, reply, a.id, a.password_hash);
      await audit(ctx, { id: a.id, username: a.username, role: a.role }, 'login', a.username, null, req.ip);
      return { admin: { id: a.id, username: a.username, role: a.role, totpEnabled: !!a.totp_secret } };
    });

    app.post('/logout', async (_req, reply) => {
      reply.clearCookie(SESSION_COOKIE, { path: '/' });
      return { ok: true };
    });

    app.register(async (s) => {
      s.addHook('preHandler', async (req) => {
        req.admin = await loadAdmin(ctx, req);
      });

      s.get('/me', async (req) => {
        const { rows } = await ctx.db.query(`SELECT totp_secret IS NOT NULL AS totp FROM admins WHERE id = $1`, [req.admin!.id]);
        return { admin: { ...req.admin, totpEnabled: rows[0]?.totp ?? false }, appName: ctx.config.appName, publicUrl: ctx.config.publicUrl };
      });

      s.post('/password', async (req, reply) => {
        const body = z.object({ current: z.string(), next: z.string().min(10, 'Nova lozinka mora imati bar 10 znakova').max(200) }).parse(req.body ?? {});
        const { rows } = await ctx.db.query(`SELECT password_hash FROM admins WHERE id = $1`, [req.admin!.id]);
        if (!rows[0] || !(await verifyPassword(body.current, rows[0].password_hash))) throw badRequest('Trenutna lozinka nije tačna');
        const hash = await hashPassword(body.next);
        await ctx.db.query(`UPDATE admins SET password_hash = $2 WHERE id = $1`, [req.admin!.id, hash]);
        setSession(ctx, reply, req.admin!.id, hash);
        await audit(ctx, req.admin!, 'password_changed', req.admin!.username, null, req.ip);
        return { ok: true };
      });

      s.post('/totp/setup', async (req) => {
        const secret = newTotpSecret();
        const label = encodeURIComponent(`${ctx.config.appName}:${req.admin!.username}`);
        return { secret, otpauthUrl: `otpauth://totp/${label}?secret=${secret}&issuer=${encodeURIComponent(ctx.config.appName)}` };
      });

      s.post('/totp/enable', async (req) => {
        const body = z.object({ secret: z.string().min(16).max(64), code: z.string() }).parse(req.body ?? {});
        if (!verifyTotp(body.secret, body.code)) throw badRequest('Kod nije ispravan. Proverite vreme na telefonu.');
        await ctx.db.query(`UPDATE admins SET totp_secret = $2 WHERE id = $1`, [req.admin!.id, body.secret]);
        await audit(ctx, req.admin!, 'totp_enabled', req.admin!.username, null, req.ip);
        return { ok: true };
      });

      s.post('/totp/disable', async (req) => {
        const body = z.object({ password: z.string() }).parse(req.body ?? {});
        const { rows } = await ctx.db.query(`SELECT password_hash FROM admins WHERE id = $1`, [req.admin!.id]);
        if (!rows[0] || !(await verifyPassword(body.password, rows[0].password_hash))) throw badRequest('Lozinka nije tačna');
        await ctx.db.query(`UPDATE admins SET totp_secret = NULL WHERE id = $1`, [req.admin!.id]);
        await audit(ctx, req.admin!, 'totp_disabled', req.admin!.username, null, req.ip);
        return { ok: true };
      });
    });
  };
}
