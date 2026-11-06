import { hmac, numericCode } from '../lib/crypto.js';
import { badRequest, conflict, notFound } from '../lib/errors.js';
import type { Queryable } from '../db/index.js';
import type { Ctx } from './context.js';

export const PAIRING_DIGITS = 8;
export const ACTIVATION_DIGITS = 8;

export function normalizeCode(raw: string): string {
  return String(raw ?? '').replace(/\D/g, '');
}

export function codeHash(ctx: Ctx, kind: 'pair' | 'act', code: string): string {
  return hmac(ctx.config.appSecret, `${kind}:${code}`);
}

export function formatCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)} ${code.slice(4)}` : code;
}

/** Novi kod uređaja za smer A (TV prikazuje kod, korisnik ga unosi na sajtu). Stari kodovi tog uređaja se brišu. */
export async function createPairingCode(ctx: Ctx, deviceId: string) {
  await ctx.db.query(`DELETE FROM pairing_codes WHERE device_id = $1 OR expires_at < now()`, [deviceId]);
  for (let i = 0; i < 10; i++) {
    const code = numericCode(PAIRING_DIGITS);
    const { rows } = await ctx.db.query<{ expires_at: Date }>(
      `INSERT INTO pairing_codes (code_hash, device_id, expires_at)
       VALUES ($1, $2, now() + make_interval(secs => $3))
       ON CONFLICT (code_hash) DO NOTHING RETURNING expires_at`,
      [codeHash(ctx, 'pair', code), deviceId, ctx.config.pairingCodeTtlSec],
    );
    if (rows[0]) {
      return {
        code,
        formatted: formatCode(code),
        expiresAt: rows[0].expires_at,
        linkUrl: `${ctx.config.publicUrl}/link?code=${code}`,
      };
    }
  }
  throw new Error('Nije moguće napraviti jedinstven kod');
}

export async function findDeviceByPairingCode(ctx: Ctx, rawCode: string): Promise<string> {
  const code = normalizeCode(rawCode);
  if (code.length !== PAIRING_DIGITS) throw badRequest(`Kod mora imati ${PAIRING_DIGITS} cifara`, 'invalid_code');
  const { rows } = await ctx.db.query<{ device_id: string }>(
    `SELECT device_id FROM pairing_codes WHERE code_hash = $1 AND expires_at > now()`,
    [codeHash(ctx, 'pair', code)],
  );
  if (!rows[0]) throw notFound('Kod nije ispravan ili je istekao. Proverite kod na ekranu televizora.', 'code_not_found');
  return rows[0].device_id;
}

/** Kod za aktivaciju (smer B): admin ga pravi, korisnik ga kuca na uređaju. */
export async function createActivationCode(
  ctx: Ctx,
  profileId: string,
  opts: { ttlHours: number; maxUses: number; adminId: string | null },
) {
  for (let i = 0; i < 10; i++) {
    const code = numericCode(ACTIVATION_DIGITS);
    const { rows } = await ctx.db.query<{ code_hash: string; expires_at: Date }>(
      `INSERT INTO activation_codes (code_hash, code_hint, profile_id, expires_at, max_uses, created_by)
       VALUES ($1, $2, $3, now() + make_interval(hours => $4), $5, $6)
       ON CONFLICT (code_hash) DO NOTHING RETURNING code_hash, expires_at`,
      [codeHash(ctx, 'act', code), `••••${code.slice(-4)}`, profileId, opts.ttlHours, opts.maxUses, opts.adminId],
    );
    if (rows[0]) {
      return { id: rows[0].code_hash, code, formatted: formatCode(code), expiresAt: rows[0].expires_at, maxUses: opts.maxUses };
    }
  }
  throw new Error('Nije moguće napraviti jedinstven kod');
}

/** Povezuje uređaj sa profilom preko koda za aktivaciju. Poziva se u transakciji. */
export async function redeemActivationCode(ctx: Ctx, q: Queryable, deviceId: string, rawCode: string) {
  const code = normalizeCode(rawCode);
  if (code.length !== ACTIVATION_DIGITS) throw badRequest(`Kod mora imati ${ACTIVATION_DIGITS} cifara`, 'invalid_code');
  const { rows } = await q.query(
    `SELECT a.*, p.max_devices, p.expires_at AS profile_expires_at
     FROM activation_codes a JOIN profiles p ON p.id = a.profile_id
     WHERE a.code_hash = $1 FOR UPDATE OF a`,
    [codeHash(ctx, 'act', code)],
  );
  const ac = rows[0];
  if (!ac || new Date(ac.expires_at).getTime() < Date.now() || ac.uses >= ac.max_uses) {
    throw notFound('Kod za aktivaciju nije ispravan ili je istekao.', 'code_not_found');
  }
  if (ac.profile_expires_at && new Date(ac.profile_expires_at).getTime() < Date.now()) {
    throw badRequest('Pretplata za ovaj nalog je istekla. Obratite se podršci.', 'profile_expired');
  }
  const cnt = await q.query<{ n: number }>(
    `SELECT count(*)::int AS n FROM devices WHERE profile_id = $1 AND id <> $2`,
    [ac.profile_id, deviceId],
  );
  if ((cnt.rows[0]?.n ?? 0) >= ac.max_devices) {
    throw conflict('Dostignut je najveći broj uređaja za ovaj nalog. Obratite se podršci.', 'max_devices');
  }
  await q.query(`UPDATE activation_codes SET uses = uses + 1 WHERE code_hash = $1`, [ac.code_hash]);
  await q.query(`UPDATE devices SET profile_id = $2 WHERE id = $1`, [deviceId, ac.profile_id]);
  return { profileId: ac.profile_id as string };
}
