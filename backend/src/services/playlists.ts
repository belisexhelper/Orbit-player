import { decryptJson, encryptJson } from '../lib/crypto.js';
import type { PlaylistInput, PlaylistSecret } from '../lib/playlist.js';
import { secretFromInput } from '../lib/playlist.js';
import type { Queryable } from '../db/index.js';
import type { Ctx } from './context.js';

export interface PlaylistRow {
  id: string;
  device_id: string | null;
  profile_id: string | null;
  name: string;
  type: 'xtream' | 'm3u';
  secret: string;
  epg_url: string;
  sort: number;
  source: string;
  created_at: Date;
  updated_at: Date;
}

/** Oblik liste koji vidi admin panel ili sajt — lozinka je skrivena. */
export function publicPlaylist(ctx: Ctx, row: PlaylistRow) {
  const s = decryptJson<PlaylistSecret>(ctx.config.encryptionKey, row.secret);
  return {
    id: row.id,
    name: row.name,
    type: row.type,
    epgUrl: row.epg_url,
    source: row.source,
    sort: row.sort,
    server: 'server' in s ? s.server : undefined,
    username: 'username' in s ? s.username : undefined,
    url: 'url' in s ? maskUrl(s.url) : undefined,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

/** Pun oblik za uređaj — sa lozinkom. */
export function devicePlaylist(ctx: Ctx, row: PlaylistRow) {
  const s = decryptJson<PlaylistSecret>(ctx.config.encryptionKey, row.secret);
  return {
    id: row.id,
    name: row.name,
    type: row.type,
    epgUrl: row.epg_url,
    source: row.device_id ? 'device' : 'profile',
    sort: row.sort,
    ...s,
    updatedAt: row.updated_at,
  };
}

export function maskUrl(url: string): string {
  try {
    const u = new URL(url);
    for (const k of [...u.searchParams.keys()]) {
      if (/pass|pwd|token|key|user/i.test(k)) u.searchParams.set(k, '•••');
    }
    if (u.password) u.password = '•••';
    return u.toString();
  } catch {
    return '•••';
  }
}

export async function insertPlaylist(
  ctx: Ctx,
  q: Queryable,
  owner: { deviceId?: string; profileId?: string },
  input: PlaylistInput,
  source: 'admin' | 'web',
): Promise<PlaylistRow> {
  const secret = encryptJson(ctx.config.encryptionKey, secretFromInput(input));
  const { rows } = await q.query<PlaylistRow>(
    `INSERT INTO playlists (device_id, profile_id, name, type, secret, epg_url, source, sort)
     VALUES ($1, $2, $3, $4, $5, $6, $7,
       COALESCE((SELECT max(sort) + 1 FROM playlists WHERE device_id IS NOT DISTINCT FROM $1 AND profile_id IS NOT DISTINCT FROM $2), 0))
     RETURNING *`,
    [owner.deviceId ?? null, owner.profileId ?? null, input.name, input.type, secret, input.epgUrl ?? '', source],
  );
  return rows[0]!;
}

export async function updatePlaylist(
  ctx: Ctx,
  q: Queryable,
  id: string,
  input: PlaylistInput,
): Promise<PlaylistRow | null> {
  const secret = encryptJson(ctx.config.encryptionKey, secretFromInput(input));
  const { rows } = await q.query<PlaylistRow>(
    `UPDATE playlists SET name = $2, type = $3, secret = $4, epg_url = $5, updated_at = now() WHERE id = $1 RETURNING *`,
    [id, input.name, input.type, secret, input.epgUrl ?? ''],
  );
  return rows[0] ?? null;
}

/**
 * Kad admin menja listu, a ne unese ponovo lozinku, zadržavamo staru.
 * Vraća pun unos spreman za updatePlaylist.
 */
export function mergeKeepPassword(ctx: Ctx, row: PlaylistRow, input: PlaylistInput): PlaylistInput {
  const old = decryptJson<PlaylistSecret>(ctx.config.encryptionKey, row.secret);
  if (input.type === 'xtream' && input.password === '__keep__' && 'password' in old) {
    return { ...input, password: old.password };
  }
  if (input.type === 'm3u' && input.url === '__keep__' && 'url' in old) {
    return { ...input, url: old.url };
  }
  return input;
}

export function decryptSecret(ctx: Ctx, row: PlaylistRow): PlaylistSecret {
  return decryptJson<PlaylistSecret>(ctx.config.encryptionKey, row.secret);
}
