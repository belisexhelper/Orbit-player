import crypto from 'node:crypto';
import { DEFAULT_SETTINGS, deepMerge, type LauncherConfig } from '../lib/settings.js';
import type { Ctx } from './context.js';
import { devicePlaylist, type PlaylistRow } from './playlists.js';

export interface DeviceRow {
  id: string;
  token_hash: string;
  name: string;
  profile_id: string | null;
  launcher_profile_id: string | null;
  settings: Record<string, unknown>;
  blocked: boolean;
  flavor: string;
  is_tv: boolean;
  manufacturer: string;
  model: string;
  android_version: string;
  sdk_int: number;
  app_version_name: string;
  app_version_code: number;
  status: Record<string, unknown>;
  last_ip: string;
  last_seen_at: Date | null;
  created_at: Date;
}

export async function getGlobalSettings(ctx: Ctx): Promise<Record<string, unknown>> {
  const { rows } = await ctx.db.query<{ value: Record<string, unknown> }>(`SELECT value FROM settings WHERE key = 'app'`);
  return rows[0]?.value ?? {};
}

/** Sastavlja kompletnu konfiguraciju koju uređaj preuzima. */
export async function buildDeviceConfig(ctx: Ctx, device: DeviceRow) {
  const [globalSettings, profileRes, playlistsRes] = await Promise.all([
    getGlobalSettings(ctx),
    device.profile_id
      ? ctx.db.query(`SELECT * FROM profiles WHERE id = $1`, [device.profile_id])
      : Promise.resolve({ rows: [] as any[] }),
    ctx.db.query<PlaylistRow>(
      `SELECT * FROM playlists WHERE device_id = $1 OR ($2::uuid IS NOT NULL AND profile_id = $2::uuid)
       ORDER BY (device_id IS NULL), sort, created_at`,
      [device.id, device.profile_id],
    ),
  ]);
  const profile = profileRes.rows[0] ?? null;
  const expired = !!(profile?.expires_at && new Date(profile.expires_at).getTime() < Date.now());

  const launcherId = device.launcher_profile_id ?? profile?.launcher_profile_id ?? null;
  let launcher: LauncherConfig | null = null;
  if (launcherId) {
    const { rows } = await ctx.db.query(`SELECT id, name, config FROM launcher_profiles WHERE id = $1`, [launcherId]);
    if (rows[0]) launcher = { ...(rows[0].config as LauncherConfig) };
  }

  const settings = deepMerge(DEFAULT_SETTINGS, globalSettings, profile?.settings ?? {}, device.settings ?? {});

  let playlists = playlistsRes.rows
    .filter((p) => !(expired && p.profile_id)) // istekli profil: sakrij liste profila, korisnikove ostaju
    .map((p) => devicePlaylist(ctx, p));
  if (device.blocked) playlists = [];

  const body = {
    device: { id: device.id, name: device.name, blocked: device.blocked },
    profile: profile
      ? { id: profile.id, name: profile.name, expiresAt: profile.expires_at, expired }
      : null,
    playlists,
    settings,
    launcher,
  };
  const configVersion = crypto.createHash('sha1').update(JSON.stringify(body)).digest('hex').slice(0, 16);
  return { ...body, configVersion, serverTime: new Date().toISOString() };
}

/** Obaveštava uređaje da ponovo preuzmu konfiguraciju (bez čuvanja komande u bazi). */
export async function notifyConfigChanged(ctx: Ctx, target: { deviceIds?: string[]; profileId?: string; launcherProfileId?: string; all?: boolean }) {
  let ids: string[] = target.deviceIds ?? [];
  if (target.all) {
    ids = ctx.hub.onlineIds();
  } else if (target.profileId) {
    const { rows } = await ctx.db.query<{ id: string }>(`SELECT id FROM devices WHERE profile_id = $1`, [target.profileId]);
    ids = ids.concat(rows.map((r) => r.id));
  } else if (target.launcherProfileId) {
    const { rows } = await ctx.db.query<{ id: string }>(
      `SELECT d.id FROM devices d LEFT JOIN profiles p ON p.id = d.profile_id
       WHERE d.launcher_profile_id = $1 OR (d.launcher_profile_id IS NULL AND p.launcher_profile_id = $1)`,
      [target.launcherProfileId],
    );
    ids = ids.concat(rows.map((r) => r.id));
  }
  for (const id of new Set(ids)) ctx.hub.send(id, { type: 'config_changed' });
}

export function deviceSummary(ctx: Ctx, d: DeviceRow & { profile_name?: string | null; launcher_name?: string | null }) {
  return {
    id: d.id,
    name: d.name,
    online: ctx.hub.isOnline(d.id),
    blocked: d.blocked,
    flavor: d.flavor,
    isTv: d.is_tv,
    manufacturer: d.manufacturer,
    model: d.model,
    androidVersion: d.android_version,
    sdkInt: d.sdk_int,
    appVersionName: d.app_version_name,
    appVersionCode: d.app_version_code,
    status: d.status,
    lastIp: d.last_ip,
    lastSeenAt: d.last_seen_at,
    createdAt: d.created_at,
    profileId: d.profile_id,
    profileName: d.profile_name ?? null,
    launcherProfileId: d.launcher_profile_id,
    launcherName: d.launcher_name ?? null,
    settings: d.settings,
  };
}
