import { z } from 'zod';
import { sha256 } from './crypto.js';

/**
 * Podrazumevana podešavanja aplikacije. Admin ih menja globalno, po profilu i po uređaju;
 * uređaj dobija spojenu (merge) vrednost: globalno ← profil ← uređaj.
 * Nova podešavanja se dodaju ovde i automatski postaju dostupna u panelu.
 */
export const DEFAULT_SETTINGS = {
  player: {
    engine: 'exo', // exo | vlc
    bufferMs: 15000,
    hardwareDecoding: true,
    autoReconnect: true,
    aspect: 'fit', // fit | fill | zoom | 16:9 | 4:3
    rememberAudioTrack: true,
  },
  subtitles: {
    enabledByDefault: false,
    preferredLanguage: 'sr',
    size: 'normal', // small | normal | large
    background: true,
  },
  ui: {
    theme: 'dark', // dark | light
    accent: '#3D8BFF',
    language: 'sr', // sr | en
    showLive: true,
    showMovies: true,
    showSeries: true,
    showDownloads: true,
    showSpeedtest: true,
  },
  parental: {
    pin: '',
    hideAdultGroups: true,
    adultKeywords: 'xxx,adult,18+,for adults',
  },
  data: {
    playlistRefreshHours: 24,
    epgRefreshHours: 12,
    epgDaysBack: 2,
  },
  app: {
    autostartOnBoot: false,
    startScreen: 'home', // home | live | last_channel
    updateChannel: 'stable', // stable | beta
    heartbeatSec: 60,
  },
  support: {
    message: '',
    contact: '',
  },
} as const;

type Json = string | number | boolean | null | Json[] | { [k: string]: Json };

/** Zadržava samo poznate ključeve iz DEFAULT_SETTINGS sa ispravnim tipom vrednosti. */
export function sanitizeSettings(input: unknown, shape: Record<string, unknown> = DEFAULT_SETTINGS): Record<string, Json> {
  const out: Record<string, Json> = {};
  if (!input || typeof input !== 'object' || Array.isArray(input)) return out;
  for (const [k, v] of Object.entries(input as Record<string, unknown>)) {
    if (!(k in shape)) continue;
    const def = shape[k];
    if (def && typeof def === 'object' && !Array.isArray(def)) {
      const nested = sanitizeSettings(v, def as Record<string, unknown>);
      if (Object.keys(nested).length) out[k] = nested;
    } else if (typeof v === typeof def && v !== null) {
      if (typeof v === 'string') out[k] = v.slice(0, 2000);
      else if (typeof v === 'number' && Number.isFinite(v)) out[k] = v;
      else if (typeof v === 'boolean') out[k] = v;
    }
  }
  return out;
}

export function deepMerge(...layers: unknown[]): Record<string, Json> {
  const out: Record<string, Json> = {};
  for (const layer of layers) {
    if (!layer || typeof layer !== 'object' || Array.isArray(layer)) continue;
    for (const [k, v] of Object.entries(layer as Record<string, Json>)) {
      const cur = out[k];
      if (v && typeof v === 'object' && !Array.isArray(v) && cur && typeof cur === 'object' && !Array.isArray(cur)) {
        out[k] = deepMerge(cur, v);
      } else if (v && typeof v === 'object' && !Array.isArray(v)) {
        out[k] = deepMerge(v);
      } else {
        out[k] = v;
      }
    }
  }
  return out;
}

// ---------- Launcher / kiosk ----------

export const launcherConfigSchema = z.object({
  /** Aplikacija radi kao početni ekran (launcher) */
  enabled: z.boolean().default(false),
  /** Korisnik ne može da isključi launcher iz aplikacije */
  locked: z.boolean().default(false),
  /**
   * Željeni nivo zaštite:
   * launcher = nivo 1/2 (HOME aplikacija), device_owner = nivo 3 (pravi kiosk, ako je uređaj pripremljen),
   * auto = najjači koji uređaj podržava
   */
  kioskLevel: z.enum(['auto', 'launcher', 'device_owner']).default('auto'),
  /** Aplikacije koje se prikazuju na početnom ekranu i smeju da se pokrenu */
  allowedApps: z
    .array(z.object({ package: z.string().trim().min(1).max(200), label: z.string().trim().max(80).default('') }))
    .max(60)
    .default([]),
  /** Šta se otvara pri paljenju: '' = početni ekran, 'live' = Live TV, 'last_channel', ili naziv paketa aplikacije */
  bootTarget: z.string().trim().max(200).default(''),
  /** Blokira sistemska Podešavanja (pun efekat samo na nivou 3) */
  blockSettings: z.boolean().default(false),
  /** Prikazuje pločicu „Podešavanja uređaja” na početnom ekranu */
  showSystemSettingsTile: z.boolean().default(true),
  /** Blokira instalaciju i brisanje aplikacija (nivo 3) */
  blockInstallUninstall: z.boolean().default(false),
  /** Sakriva statusnu traku i obaveštenja (nivo 3) */
  hideStatusBar: z.boolean().default(false),
  /** PIN za izlaz iz kiosk moda na samom uređaju (čuva se samo heš) */
  exitPin: z.string().regex(/^\d{4,8}$/, 'PIN mora imati 4–8 cifara').optional(),
  exitPinHash: z.string().optional(),
  branding: z
    .object({
      title: z.string().max(60).default(''),
      logoUrl: z.string().max(1000).default(''),
      backgroundUrl: z.string().max(1000).default(''),
      accent: z.string().max(20).default(''),
      welcomeText: z.string().max(300).default(''),
    })
    .default({ title: '', logoUrl: '', backgroundUrl: '', accent: '', welcomeText: '' }),
});

export type LauncherConfig = z.infer<typeof launcherConfigSchema>;

/** Validira konfiguraciju i zamenjuje PIN njegovim hešom. */
export function prepareLauncherConfig(input: unknown, previous?: LauncherConfig): LauncherConfig {
  const cfg = launcherConfigSchema.parse(input);
  const clear = !!input && typeof input === 'object' && (input as Record<string, unknown>).clearExitPin === true;
  if (clear) {
    delete cfg.exitPinHash;
  } else if (cfg.exitPin) {
    cfg.exitPinHash = sha256(`launcher-pin:${cfg.exitPin}`);
  } else if (!cfg.exitPinHash && previous?.exitPinHash) {
    cfg.exitPinHash = previous.exitPinHash;
  }
  delete cfg.exitPin;
  return cfg;
}
