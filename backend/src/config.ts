import path from 'node:path';

function required(name: string, fallback?: string): string {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`Nedostaje promenljiva okruženja ${name}`);
  return v;
}

export interface AppConfig {
  port: number;
  host: string;
  /** postgres://... ili pglite:./data/db (ugrađena baza, za probu i razvoj) ili pglite:memory */
  databaseUrl: string;
  /** 32 bajta u hex obliku (64 znaka) — ključ za šifrovanje IPTV podataka */
  encryptionKey: Buffer;
  /** tajna za potpisivanje sesija i heširanje kodova */
  appSecret: string;
  publicUrl: string;
  dataDir: string;
  webDir: string | null;
  allowPrivateUrls: boolean;
  cookieSecure: boolean;
  appName: string;
  pairingCodeTtlSec: number;
  logLevel: string;
}

export function loadConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  const keyHex = overrides.encryptionKey
    ? overrides.encryptionKey.toString('hex')
    : required('ENCRYPTION_KEY');
  if (!/^[0-9a-fA-F]{64}$/.test(keyHex)) {
    throw new Error('ENCRYPTION_KEY mora imati tačno 64 hex znaka (32 bajta). Napravi ga sa: openssl rand -hex 32');
  }
  const appSecret = overrides.appSecret ?? required('APP_SECRET');
  if (appSecret.length < 32) throw new Error('APP_SECRET mora imati bar 32 znaka. Napravi ga sa: openssl rand -hex 32');
  const dataDir = overrides.dataDir ?? process.env.DATA_DIR ?? path.resolve('data');
  const publicUrl = (overrides.publicUrl ?? process.env.PUBLIC_URL ?? 'http://localhost:8080').replace(/\/$/, '');
  return {
    port: overrides.port ?? Number(process.env.PORT ?? 8080),
    host: overrides.host ?? process.env.HOST ?? '0.0.0.0',
    databaseUrl: overrides.databaseUrl ?? process.env.DATABASE_URL ?? `pglite:${path.join(dataDir, 'db')}`,
    encryptionKey: Buffer.from(keyHex, 'hex'),
    appSecret,
    publicUrl,
    dataDir,
    webDir: overrides.webDir !== undefined ? overrides.webDir : process.env.WEB_DIR ?? null,
    allowPrivateUrls: overrides.allowPrivateUrls ?? process.env.ALLOW_PRIVATE_URLS === 'true',
    cookieSecure: overrides.cookieSecure ?? publicUrl.startsWith('https://'),
    appName: overrides.appName ?? process.env.APP_NAME ?? 'Orbit Player',
    pairingCodeTtlSec: overrides.pairingCodeTtlSec ?? Number(process.env.PAIRING_CODE_TTL_SEC ?? 900),
    logLevel: overrides.logLevel ?? process.env.LOG_LEVEL ?? 'info',
  };
}
