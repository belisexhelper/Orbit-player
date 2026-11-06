import type { Ctx } from './context.js';

export interface AdminIdentity {
  id: string;
  username: string;
  role: 'super' | 'support' | 'reseller';
}

export async function audit(
  ctx: Ctx,
  admin: AdminIdentity | null,
  action: string,
  target: string,
  details: Record<string, unknown> | null,
  ip: string,
) {
  try {
    await ctx.db.query(
      `INSERT INTO audit_log (admin_id, admin_username, action, target, details, ip) VALUES ($1, $2, $3, $4, $5, $6)`,
      [admin?.id ?? null, admin?.username ?? '', action, target, details ? JSON.stringify(details) : null, ip],
    );
  } catch (e) {
    ctx.log.error({ err: e }, 'audit log write failed');
  }
}
