import type { Ctx } from './context.js';

export const COMMAND_TYPES = [
  'reload_config', // ponovo preuzmi konfiguraciju
  'refresh_playlists', // osveži liste kanala i EPG
  'show_message', // { text, durationSec }
  'restart_app',
  'reboot', // nivo 3 (Device Owner)
  'play_channel', // { number } ili { name }
  'open_app', // { package }
  'go_home',
  'clear_cache',
  'check_update', // proveri i instaliraj novu verziju
  'run_diagnostics', // pokreni dijagnostiku i pošalji izveštaj
  'unpair', // odjavi uređaj (briše lokalne podatke)
  'exit_kiosk', // privremeno izađi iz kiosk moda (servisni pristup)
] as const;

export type CommandType = (typeof COMMAND_TYPES)[number];

export interface CommandRow {
  id: string;
  device_id: string;
  type: string;
  payload: Record<string, unknown>;
  status: string;
  result: unknown;
  created_by: string | null;
  created_at: Date;
  sent_at: Date | null;
  done_at: Date | null;
  expires_at: Date;
}

export function commandMessage(c: CommandRow) {
  return { type: 'command', command: { id: c.id, type: c.type, payload: c.payload, createdAt: c.created_at } };
}

/** Pravi komandu i odmah je šalje ako je uređaj na mreži; inače čeka sledeće povezivanje. */
export async function createCommand(
  ctx: Ctx,
  deviceId: string,
  type: CommandType,
  payload: Record<string, unknown>,
  adminId: string | null,
  ttlSec = 24 * 3600,
): Promise<CommandRow> {
  const { rows } = await ctx.db.query<CommandRow>(
    `INSERT INTO commands (device_id, type, payload, created_by, expires_at)
     VALUES ($1, $2, $3, $4, now() + make_interval(secs => $5)) RETURNING *`,
    [deviceId, type, JSON.stringify(payload), adminId, ttlSec],
  );
  const cmd = rows[0]!;
  if (ctx.hub.send(deviceId, commandMessage(cmd))) {
    await ctx.db.query(`UPDATE commands SET status = 'sent', sent_at = now() WHERE id = $1`, [cmd.id]);
    cmd.status = 'sent';
  }
  return cmd;
}

/** Komande koje čekaju na uređaj (koristi se pri povezivanju i kod rezervnog „heartbeat” kanala). */
export async function takePendingCommands(ctx: Ctx, deviceId: string): Promise<CommandRow[]> {
  await ctx.db.query(
    `UPDATE commands SET status = 'expired' WHERE device_id = $1 AND status IN ('pending','sent') AND expires_at < now()`,
    [deviceId],
  );
  const { rows } = await ctx.db.query<CommandRow>(
    `UPDATE commands SET status = 'sent', sent_at = now()
     WHERE device_id = $1 AND status = 'pending'
     RETURNING *`,
    [deviceId],
  );
  return rows.sort((a, b) => +new Date(a.created_at) - +new Date(b.created_at));
}

export async function completeCommand(ctx: Ctx, deviceId: string, id: string, ok: boolean, result: unknown) {
  await ctx.db.query(
    `UPDATE commands SET status = $3, result = $4, done_at = now() WHERE id = $1 AND device_id = $2 AND status IN ('pending','sent')`,
    [id, deviceId, ok ? 'done' : 'failed', JSON.stringify(result ?? null)],
  );
}
