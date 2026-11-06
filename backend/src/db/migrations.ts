import type { Db } from './index.js';

/**
 * Migracije se izvršavaju redom i samo jednom. Nikad ne menjaj postojeću migraciju —
 * dodaj novu na kraj liste.
 */
const migrations: { id: number; name: string; sql: string }[] = [
  {
    id: 1,
    name: 'init',
    sql: `
CREATE TABLE admins (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  username text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('super','support','reseller')),
  totp_secret text,
  disabled boolean NOT NULL DEFAULT false,
  failed_logins int NOT NULL DEFAULT 0,
  locked_until timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_login_at timestamptz
);

CREATE TABLE launcher_profiles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  config jsonb NOT NULL DEFAULT '{}'::jsonb,
  owner_admin_id uuid REFERENCES admins(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE profiles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  note text NOT NULL DEFAULT '',
  contact text NOT NULL DEFAULT '',
  expires_at timestamptz,
  max_devices int NOT NULL DEFAULT 1,
  launcher_profile_id uuid REFERENCES launcher_profiles(id) ON DELETE SET NULL,
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  owner_admin_id uuid REFERENCES admins(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  token_hash text NOT NULL,
  name text NOT NULL DEFAULT '',
  profile_id uuid REFERENCES profiles(id) ON DELETE SET NULL,
  launcher_profile_id uuid REFERENCES launcher_profiles(id) ON DELETE SET NULL,
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  blocked boolean NOT NULL DEFAULT false,
  flavor text NOT NULL DEFAULT 'pro',
  is_tv boolean NOT NULL DEFAULT false,
  manufacturer text NOT NULL DEFAULT '',
  model text NOT NULL DEFAULT '',
  android_version text NOT NULL DEFAULT '',
  sdk_int int NOT NULL DEFAULT 0,
  app_version_name text NOT NULL DEFAULT '',
  app_version_code int NOT NULL DEFAULT 0,
  status jsonb NOT NULL DEFAULT '{}'::jsonb,
  last_ip text NOT NULL DEFAULT '',
  last_seen_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX devices_profile_idx ON devices(profile_id);
CREATE INDEX devices_last_seen_idx ON devices(last_seen_at);

CREATE TABLE playlists (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid REFERENCES devices(id) ON DELETE CASCADE,
  profile_id uuid REFERENCES profiles(id) ON DELETE CASCADE,
  name text NOT NULL,
  type text NOT NULL CHECK (type IN ('xtream','m3u')),
  secret text NOT NULL,
  epg_url text NOT NULL DEFAULT '',
  sort int NOT NULL DEFAULT 0,
  source text NOT NULL DEFAULT 'admin',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((device_id IS NULL) <> (profile_id IS NULL))
);
CREATE INDEX playlists_device_idx ON playlists(device_id);
CREATE INDEX playlists_profile_idx ON playlists(profile_id);

CREATE TABLE pairing_codes (
  code_hash text PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pairing_codes_device_idx ON pairing_codes(device_id);

CREATE TABLE activation_codes (
  code_hash text PRIMARY KEY,
  code_hint text NOT NULL,
  profile_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  max_uses int NOT NULL DEFAULT 1,
  uses int NOT NULL DEFAULT 0,
  created_by uuid REFERENCES admins(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX activation_codes_profile_idx ON activation_codes(profile_id);

CREATE TABLE commands (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','sent','done','failed','expired')),
  result jsonb,
  created_by uuid REFERENCES admins(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  sent_at timestamptz,
  done_at timestamptz,
  expires_at timestamptz NOT NULL
);
CREATE INDEX commands_device_status_idx ON commands(device_id, status);

CREATE TABLE settings (
  key text PRIMARY KEY,
  value jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE releases (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  flavor text NOT NULL CHECK (flavor IN ('pro','lite')),
  channel text NOT NULL DEFAULT 'stable' CHECK (channel IN ('stable','beta')),
  version_code int NOT NULL,
  version_name text NOT NULL,
  file_name text NOT NULL,
  size bigint NOT NULL,
  sha256 text NOT NULL,
  changelog text NOT NULL DEFAULT '',
  rollout_percent int NOT NULL DEFAULT 100 CHECK (rollout_percent BETWEEN 0 AND 100),
  mandatory boolean NOT NULL DEFAULT false,
  min_sdk int NOT NULL DEFAULT 21,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (flavor, version_code)
);

CREATE TABLE device_logs (
  id bigserial PRIMARY KEY,
  device_id uuid NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  level text NOT NULL,
  kind text NOT NULL DEFAULT 'log',
  message text NOT NULL,
  data jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_logs_device_idx ON device_logs(device_id, created_at DESC);

CREATE TABLE audit_log (
  id bigserial PRIMARY KEY,
  admin_id uuid REFERENCES admins(id) ON DELETE SET NULL,
  admin_username text NOT NULL DEFAULT '',
  action text NOT NULL,
  target text NOT NULL DEFAULT '',
  details jsonb,
  ip text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_created_idx ON audit_log(created_at DESC);
`,
  },
];

export async function migrate(db: Db): Promise<void> {
  await db.query(`CREATE TABLE IF NOT EXISTS schema_migrations (
    id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`);
  const { rows } = await db.query<{ id: number }>('SELECT id FROM schema_migrations');
  const done = new Set(rows.map((r) => r.id));
  for (const m of migrations) {
    if (done.has(m.id)) continue;
    await db.tx(async (q) => {
      for (const stmt of splitSql(m.sql)) await q.query(stmt);
      await q.query('INSERT INTO schema_migrations (id, name) VALUES ($1, $2)', [m.id, m.name]);
    });
  }
}

function splitSql(sql: string): string[] {
  return sql
    .split(/;\s*\n/)
    .map((s) => s.trim())
    .filter(Boolean);
}
