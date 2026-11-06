import { hashPassword } from '../lib/crypto.js';
import type { Ctx } from './context.js';

export async function createAdmin(ctx: Pick<Ctx, 'db'>, username: string, password: string, role: 'super' | 'support' | 'reseller') {
  const hash = await hashPassword(password);
  const { rows } = await ctx.db.query(
    `INSERT INTO admins (username, password_hash, role) VALUES ($1, $2, $3) RETURNING id, username, role`,
    [username.trim().toLowerCase(), hash, role],
  );
  return rows[0];
}

/** Na prvom pokretanju pravi glavnog admina iz ADMIN_USERNAME / ADMIN_PASSWORD. */
export async function ensureBootstrapAdmin(ctx: Ctx) {
  const { rows } = await ctx.db.query<{ n: number }>(`SELECT count(*)::int AS n FROM admins`);
  if ((rows[0]?.n ?? 0) > 0) return;
  const u = process.env.ADMIN_USERNAME;
  const p = process.env.ADMIN_PASSWORD;
  if (u && p) {
    if (p.length < 10) throw new Error('ADMIN_PASSWORD mora imati bar 10 znakova');
    await createAdmin(ctx, u, p, 'super');
    ctx.log.info(`Napravljen glavni admin "${u}". Preporuka: obrišite ADMIN_PASSWORD iz .env fajla.`);
  } else {
    ctx.log.warn('Nema nijednog admina. Postavite ADMIN_USERNAME i ADMIN_PASSWORD i ponovo pokrenite server.');
  }
}
