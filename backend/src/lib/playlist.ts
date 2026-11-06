import { z } from 'zod';
import { badRequest } from './errors.js';
import { parseHttpUrl, safeFetchText } from './net.js';

const optionalUrl = z
  .string()
  .trim()
  .max(2000)
  .optional()
  .transform((v) => v ?? '')
  .refine((v) => v === '' || /^https?:\/\//i.test(v), 'EPG adresa mora počinjati sa http:// ili https://');

export const playlistInputSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('xtream'),
    name: z.string().trim().min(1, 'Unesite naziv').max(80),
    server: z.string().trim().min(4, 'Unesite adresu servera').max(500),
    username: z.string().trim().min(1, 'Unesite korisničko ime').max(200),
    password: z.string().min(1, 'Unesite lozinku').max(200),
    epgUrl: optionalUrl,
  }),
  z.object({
    type: z.literal('m3u'),
    name: z.string().trim().min(1, 'Unesite naziv').max(80),
    url: z.string().trim().min(8, 'Unesite adresu liste').max(2000),
    epgUrl: optionalUrl,
  }),
]);

export type PlaylistInput = z.infer<typeof playlistInputSchema>;

export type PlaylistSecret = { server: string; username: string; password: string } | { url: string };

/** Normalizuje adresu Xtream servera: dodaje http:// i uklanja /player_api.php, /get.php i kosu crtu na kraju. */
export function normalizeXtreamServer(raw: string): string {
  let s = raw.trim();
  if (!/^https?:\/\//i.test(s)) s = `http://${s}`;
  const u = parseHttpUrl(s);
  let p = u.pathname.replace(/\/(player_api|get|xmltv)\.php$/i, '').replace(/\/+$/, '');
  return `${u.protocol}//${u.host}${p}`;
}

export function secretFromInput(input: PlaylistInput): PlaylistSecret {
  if (input.type === 'xtream') {
    return { server: normalizeXtreamServer(input.server), username: input.username, password: input.password };
  }
  return { url: parseHttpUrl(input.url).toString() };
}

export interface PlaylistTestResult {
  ok: boolean;
  message: string;
  info: Record<string, unknown>;
}

export async function testPlaylist(
  type: 'xtream' | 'm3u',
  secret: PlaylistSecret,
  allowPrivate: boolean,
): Promise<PlaylistTestResult> {
  if (type === 'xtream' && 'server' in secret) {
    const base = `${secret.server}/player_api.php?username=${encodeURIComponent(secret.username)}&password=${encodeURIComponent(secret.password)}`;
    const res = await safeFetchText(base, { allowPrivate, maxBytes: 512 * 1024 });
    let json: any;
    try {
      json = JSON.parse(res.text);
    } catch {
      return { ok: false, message: `Server nije vratio Xtream odgovor (HTTP ${res.status}). Proverite adresu servera.`, info: {} };
    }
    const ui = json?.user_info;
    if (!ui || Number(ui.auth) !== 1) {
      return { ok: false, message: 'Pogrešno korisničko ime ili lozinka.', info: {} };
    }
    const info: Record<string, unknown> = {
      status: ui.status ?? '',
      expiresAt: ui.exp_date ? new Date(Number(ui.exp_date) * 1000).toISOString() : null,
      maxConnections: ui.max_connections != null ? Number(ui.max_connections) : null,
      activeConnections: ui.active_cons != null ? Number(ui.active_cons) : null,
    };
    if (String(ui.status ?? '').toLowerCase() !== 'active') {
      return { ok: false, message: `Nalog nije aktivan (status: ${ui.status ?? 'nepoznat'}).`, info };
    }
    const counts = await Promise.all(
      ['get_live_categories', 'get_vod_categories', 'get_series_categories'].map(async (action) => {
        try {
          const r = await safeFetchText(`${base}&action=${action}`, { allowPrivate, maxBytes: 2 * 1024 * 1024 });
          const arr = JSON.parse(r.text);
          return Array.isArray(arr) ? arr.length : 0;
        } catch {
          return null;
        }
      }),
    );
    info.liveCategories = counts[0];
    info.vodCategories = counts[1];
    info.seriesCategories = counts[2];
    return { ok: true, message: 'Uspešno povezano sa serverom.', info };
  }

  if (type === 'm3u' && 'url' in secret) {
    const res = await safeFetchText(secret.url, { allowPrivate, maxBytes: 60 * 1024 * 1024, timeoutMs: 30_000 });
    if (res.status >= 400) return { ok: false, message: `Lista nije dostupna (HTTP ${res.status}).`, info: {} };
    const text = res.text.replace(/^﻿/, '');
    if (!text.trimStart().startsWith('#EXTM3U')) {
      return { ok: false, message: 'Adresa ne vraća M3U listu.', info: {} };
    }
    const channels = (text.match(/^#EXTINF/gm) ?? []).length;
    const header = text.split('\n', 1)[0] ?? '';
    const epg = /(?:url-tvg|x-tvg-url)="([^"]+)"/i.exec(header)?.[1] ?? '';
    if (channels === 0) return { ok: false, message: 'Lista je prazna.', info: {} };
    return {
      ok: true,
      message: `Uspešno. Pronađeno stavki: ${channels}${res.truncated ? '+' : ''}.`,
      info: { channels, suggestedEpgUrl: epg.split(',')[0] ?? '' },
    };
  }
  throw badRequest('Nepoznat tip liste');
}
