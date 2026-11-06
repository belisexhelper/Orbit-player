import crypto from 'node:crypto';

// ---------- Šifrovanje IPTV podataka (AES-256-GCM) ----------

export function encryptJson(key: Buffer, value: unknown): string {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const data = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return `v1.${iv.toString('base64url')}.${tag.toString('base64url')}.${data.toString('base64url')}`;
}

export function decryptJson<T = unknown>(key: Buffer, blob: string): T {
  const [v, ivB, tagB, dataB] = blob.split('.');
  if (v !== 'v1' || !ivB || !tagB || dataB === undefined) throw new Error('Neispravan šifrovan zapis');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(ivB, 'base64url'));
  decipher.setAuthTag(Buffer.from(tagB, 'base64url'));
  const out = Buffer.concat([decipher.update(Buffer.from(dataB, 'base64url')), decipher.final()]);
  return JSON.parse(out.toString('utf8')) as T;
}

// ---------- Heševi i tokeni ----------

export function sha256(s: string): string {
  return crypto.createHash('sha256').update(s).digest('hex');
}

export function hmac(secret: string, s: string): string {
  return crypto.createHmac('sha256', secret).update(s).digest('hex');
}

export function randomToken(bytes = 32): string {
  return crypto.randomBytes(bytes).toString('base64url');
}

export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  return ab.length === bb.length && crypto.timingSafeEqual(ab, bb);
}

/** Numerički kod koji ne počinje nulom (npr. 8 cifara: 10000000–99999999). */
export function numericCode(digits: number): string {
  const min = 10 ** (digits - 1);
  const max = 10 ** digits;
  return String(crypto.randomInt(min, max));
}

// ---------- Lozinke (scrypt) ----------

export async function hashPassword(password: string): Promise<string> {
  const salt = crypto.randomBytes(16);
  const key = await scrypt(password, salt);
  return `scrypt$${salt.toString('base64url')}$${key.toString('base64url')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [alg, saltB, keyB] = stored.split('$');
  if (alg !== 'scrypt' || !saltB || !keyB) return false;
  const key = await scrypt(password, Buffer.from(saltB, 'base64url'));
  const expected = Buffer.from(keyB, 'base64url');
  return key.length === expected.length && crypto.timingSafeEqual(key, expected);
}

function scrypt(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    crypto.scrypt(password, salt, 64, { N: 16384, r: 8, p: 1 }, (err, key) => (err ? reject(err) : resolve(key))),
  );
}

// ---------- Potpisani tokeni (sesije) ----------

export function signToken(secret: string, payload: Record<string, unknown>, ttlSec: number): string {
  const body = Buffer.from(JSON.stringify({ ...payload, exp: Math.floor(Date.now() / 1000) + ttlSec })).toString(
    'base64url',
  );
  return `${body}.${hmac(secret, body)}`;
}

export function verifyToken<T extends Record<string, unknown>>(secret: string, token: string | undefined): T | null {
  if (!token) return null;
  const [body, sig] = token.split('.');
  if (!body || !sig || !safeEqual(sig, hmac(secret, body))) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (typeof payload.exp !== 'number' || payload.exp < Date.now() / 1000) return null;
    return payload as T;
  } catch {
    return null;
  }
}

// ---------- TOTP (RFC 6238) za dvofaktorsku prijavu ----------

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(s: string): Buffer {
  const clean = s.replace(/=+$/, '').replace(/\s/g, '').toUpperCase();
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error('Neispravan base32');
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

export function newTotpSecret(): string {
  return base32Encode(crypto.randomBytes(20));
}

export function totpCode(secret: string, timeMs = Date.now(), step = 30): string {
  const counter = Math.floor(timeMs / 1000 / step);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const h = crypto.createHmac('sha1', base32Decode(secret)).update(buf).digest();
  const offset = h[h.length - 1]! & 0xf;
  const bin = ((h[offset]! & 0x7f) << 24) | (h[offset + 1]! << 16) | (h[offset + 2]! << 8) | h[offset + 3]!;
  return String(bin % 1_000_000).padStart(6, '0');
}

export function verifyTotp(secret: string, code: string, timeMs = Date.now()): boolean {
  const c = code.replace(/\s/g, '');
  if (!/^\d{6}$/.test(c)) return false;
  for (const drift of [-1, 0, 1]) {
    if (safeEqual(totpCode(secret, timeMs + drift * 30_000), c)) return true;
  }
  return false;
}
