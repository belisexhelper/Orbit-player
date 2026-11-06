import type { FastifyRequest } from 'fastify';
import { notFound } from '../../lib/errors.js';
import type { Ctx } from '../../services/context.js';
import type { DeviceRow } from '../../services/devices.js';

/** Reseler vidi samo svoje profile i uređaje vezane za te profile. */
export function isReseller(req: FastifyRequest) {
  return req.admin?.role === 'reseller';
}

export async function getProfileOr404(ctx: Ctx, req: FastifyRequest, id: string) {
  const { rows } = await ctx.db.query(`SELECT * FROM profiles WHERE id = $1`, [id]);
  const p = rows[0];
  if (!p || (isReseller(req) && p.owner_admin_id !== req.admin!.id)) throw notFound('Profil nije pronađen');
  return p;
}

export async function getDeviceOr404(ctx: Ctx, req: FastifyRequest, id: string): Promise<DeviceRow & { profile_owner: string | null }> {
  const { rows } = await ctx.db.query(
    `SELECT d.*, p.owner_admin_id AS profile_owner FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id WHERE d.id = $1`,
    [id],
  );
  const d = rows[0];
  if (!d || (isReseller(req) && d.profile_owner !== req.admin!.id)) throw notFound('Uređaj nije pronađen');
  return d;
}

export async function getLauncherOr404(ctx: Ctx, req: FastifyRequest, id: string) {
  const { rows } = await ctx.db.query(`SELECT * FROM launcher_profiles WHERE id = $1`, [id]);
  const l = rows[0];
  if (!l || (isReseller(req) && l.owner_admin_id !== req.admin!.id && l.owner_admin_id !== null)) {
    throw notFound('Launcher profil nije pronađen');
  }
  return l;
}
