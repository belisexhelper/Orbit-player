import dns from 'node:dns/promises';
import net from 'node:net';
import { badRequest } from './errors.js';

/** Da li je IP adresa privatna/lokalna (zaštita od SSRF napada preko unosa URL-a). */
export function isPrivateIp(ip: string): boolean {
  if (net.isIPv4(ip)) {
    const [a, b] = ip.split('.').map(Number) as [number, number];
    return (
      a === 0 ||
      a === 10 ||
      a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 198 && (b === 18 || b === 19)) ||
      a >= 224
    );
  }
  const v6 = ip.toLowerCase();
  if (v6.startsWith('::ffff:')) return isPrivateIp(v6.slice(7));
  return v6 === '::' || v6 === '::1' || v6.startsWith('fc') || v6.startsWith('fd') || v6.startsWith('fe80');
}

export interface SafeFetchOptions {
  allowPrivate: boolean;
  timeoutMs?: number;
  maxBytes?: number;
}

export function parseHttpUrl(raw: string): URL {
  let u: URL;
  try {
    u = new URL(raw.trim());
  } catch {
    throw badRequest('Neispravna adresa (URL)', 'invalid_url');
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') throw badRequest('Adresa mora počinjati sa http:// ili https://', 'invalid_url');
  return u;
}

async function assertPublicHost(u: URL, allowPrivate: boolean) {
  if (allowPrivate) return;
  const host = u.hostname.replace(/^\[|\]$/g, '');
  const addrs = net.isIP(host) ? [host] : (await dns.lookup(host, { all: true }).catch(() => [])).map((a) => a.address);
  if (addrs.length === 0) throw badRequest(`Server ${host} nije pronađen`, 'dns_failed');
  if (addrs.some(isPrivateIp)) throw badRequest('Lokalne i privatne adrese nisu dozvoljene', 'private_url');
}

/** Preuzima tekst sa spoljne adrese sa ograničenjem vremena i veličine. */
export async function safeFetchText(rawUrl: string, opts: SafeFetchOptions): Promise<{ status: number; text: string; truncated: boolean }> {
  let url = parseHttpUrl(rawUrl);
  const timeoutMs = opts.timeoutMs ?? 15_000;
  const maxBytes = opts.maxBytes ?? 5 * 1024 * 1024;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    // Ručno pratimo preusmerenja da bi svako bilo provereno
    for (let hop = 0; hop < 5; hop++) {
      await assertPublicHost(url, opts.allowPrivate);
      const res = await fetch(url, {
        redirect: 'manual',
        signal: ctrl.signal,
        headers: { 'user-agent': 'OrbitPlayer/1.0', accept: '*/*' },
      });
      if (res.status >= 300 && res.status < 400 && res.headers.get('location')) {
        url = parseHttpUrl(new URL(res.headers.get('location')!, url).toString());
        continue;
      }
      const reader = res.body?.getReader();
      const chunks: Uint8Array[] = [];
      let size = 0;
      let truncated = false;
      if (reader) {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > maxBytes) {
            truncated = true;
            chunks.push(value.subarray(0, value.byteLength - (size - maxBytes)));
            await reader.cancel().catch(() => undefined);
            break;
          }
          chunks.push(value);
        }
      }
      return { status: res.status, text: Buffer.concat(chunks).toString('utf8'), truncated };
    }
    throw badRequest('Previše preusmerenja', 'too_many_redirects');
  } catch (e: any) {
    if (e?.statusCode) throw e;
    if (e?.name === 'AbortError') throw badRequest('Server ne odgovara (isteklo vreme)', 'timeout');
    throw badRequest(`Server nije dostupan: ${e?.cause?.code ?? e?.message ?? 'nepoznata greška'}`, 'fetch_failed');
  } finally {
    clearTimeout(timer);
  }
}
