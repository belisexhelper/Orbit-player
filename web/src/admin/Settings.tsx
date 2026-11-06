import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { del, post, put } from '../api';
import { Confirm, useToast } from '../components/ui';
import { PageHead, useAuth, useLoad } from './Admin';

const GROUPS: Record<string, string> = {
  player: 'Plejer',
  subtitles: 'Prevodi',
  ui: 'Izgled',
  parental: 'Roditeljska zaštita',
  data: 'Osvežavanje podataka',
  app: 'Aplikacija',
  support: 'Podrška',
};

const LABELS: Record<string, string> = {
  'player.engine': 'Podrazumevani plejer',
  'player.bufferMs': 'Bafer (milisekunde)',
  'player.hardwareDecoding': 'Hardversko dekodiranje',
  'player.autoReconnect': 'Automatski ponovo poveži pri prekidu',
  'player.aspect': 'Odnos slike',
  'player.rememberAudioTrack': 'Pamti izabran audio zapis',
  'subtitles.enabledByDefault': 'Prevodi uključeni automatski',
  'subtitles.preferredLanguage': 'Željeni jezik prevoda (npr. sr, en)',
  'subtitles.size': 'Veličina prevoda',
  'subtitles.background': 'Tamna pozadina iza prevoda',
  'ui.theme': 'Tema',
  'ui.accent': 'Boja akcenta',
  'ui.language': 'Jezik aplikacije',
  'ui.showLive': 'Prikaži Live TV',
  'ui.showMovies': 'Prikaži filmove',
  'ui.showSeries': 'Prikaži serije',
  'ui.showDownloads': 'Prikaži preuzimanja (offline)',
  'ui.showSpeedtest': 'Prikaži test brzine i dijagnostiku',
  'parental.pin': 'Roditeljski PIN (4 cifre)',
  'parental.hideAdultGroups': 'Sakrij grupe za odrasle',
  'parental.adultKeywords': 'Reči koje označavaju grupe za odrasle',
  'data.playlistRefreshHours': 'Osvežavanje liste (sati)',
  'data.epgRefreshHours': 'Osvežavanje TV vodiča (sati)',
  'data.epgDaysBack': 'Dana unazad u TV vodiču',
  'app.autostartOnBoot': 'Pokreni aplikaciju pri paljenju uređaja',
  'app.startScreen': 'Početni ekran',
  'app.updateChannel': 'Kanal ažuriranja',
  'app.heartbeatSec': 'Javljanje serveru (sekunde)',
  'support.message': 'Poruka podrške u aplikaciji',
  'support.contact': 'Kontakt podrške',
};

const ENUMS: Record<string, [string, string][]> = {
  'player.engine': [
    ['exo', 'ExoPlayer (preporučeno)'],
    ['vlc', 'VLC'],
  ],
  'player.aspect': [
    ['fit', 'Uklopi'],
    ['fill', 'Popuni'],
    ['zoom', 'Uvećaj'],
    ['16:9', '16:9'],
    ['4:3', '4:3'],
  ],
  'subtitles.size': [
    ['small', 'Mala'],
    ['normal', 'Normalna'],
    ['large', 'Velika'],
  ],
  'ui.theme': [
    ['dark', 'Tamna'],
    ['light', 'Svetla'],
  ],
  'ui.language': [
    ['sr', 'Srpski'],
    ['en', 'English'],
  ],
  'app.startScreen': [
    ['home', 'Početna'],
    ['live', 'Live TV'],
    ['last_channel', 'Poslednji gledani kanal'],
  ],
  'app.updateChannel': [
    ['stable', 'Stabilne verzije'],
    ['beta', 'Beta verzije'],
  ],
};

type Obj = Record<string, any>;

/**
 * Uređivač podešavanja napravljen iz podrazumevanih vrednosti sa servera.
 * sparse = prikazuje samo ono što se menja na ovom nivou; prazno polje znači „nasleđeno”.
 */
export function SettingsEditor({ value, sparse, onSave }: { value: Obj; sparse?: boolean; onSave: (v: Obj) => Promise<void> }) {
  const { data } = useLoad<any>('/api/admin/settings');
  const [v, setV] = useState<Obj>(() => structuredClone(value ?? {}));
  const [busy, setBusy] = useState(false);
  if (!data) return null;
  const defaults: Obj = data.defaults;
  const inherited: Obj = sparse ? deepMerge(defaults, data.global) : defaults;

  const setField = (g: string, k: string, val: any) =>
    setV((o) => {
      const n = structuredClone(o);
      if (val === undefined) {
        if (n[g]) delete n[g][k];
        if (n[g] && Object.keys(n[g]).length === 0) delete n[g];
      } else {
        n[g] = { ...(n[g] ?? {}), [k]: val };
      }
      return n;
    });

  return (
    <form
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        try {
          await onSave(v);
        } finally {
          setBusy(false);
        }
      }}
    >
      {Object.keys(defaults).map((g) => (
        <fieldset key={g} style={{ border: 0, padding: 0, margin: '0 0 18px' }}>
          <h3 style={{ fontSize: '1rem', marginBottom: 10 }}>{GROUPS[g] ?? g}</h3>
          <div className="grid2">
            {Object.keys(defaults[g]).map((k) => {
              const path = `${g}.${k}`;
              const def = defaults[g][k];
              const cur = v[g]?.[k];
              const inh = inherited[g]?.[k];
              const label = LABELS[path] ?? path;
              if (typeof def === 'boolean') {
                if (sparse) {
                  return (
                    <label className="field" key={path}>
                      <span>{label}</span>
                      <select value={cur === undefined ? '' : cur ? '1' : '0'} onChange={(e) => setField(g, k, e.target.value === '' ? undefined : e.target.value === '1')}>
                        <option value="">Nasleđeno ({inh ? 'da' : 'ne'})</option>
                        <option value="1">Da</option>
                        <option value="0">Ne</option>
                      </select>
                    </label>
                  );
                }
                return (
                  <label className="check" key={path} style={{ alignSelf: 'center' }}>
                    <input type="checkbox" checked={cur ?? def} onChange={(e) => setField(g, k, e.target.checked)} />
                    <span>{label}</span>
                  </label>
                );
              }
              if (ENUMS[path]) {
                return (
                  <label className="field" key={path}>
                    <span>{label}</span>
                    <select value={cur ?? (sparse ? '' : def)} onChange={(e) => setField(g, k, e.target.value === '' ? undefined : e.target.value)}>
                      {sparse && <option value="">Nasleđeno ({ENUMS[path]!.find(([x]) => x === inh)?.[1] ?? inh})</option>}
                      {ENUMS[path]!.map(([x, l]) => (
                        <option key={x} value={x}>
                          {l}
                        </option>
                      ))}
                    </select>
                  </label>
                );
              }
              const isNum = typeof def === 'number';
              return (
                <label className="field" key={path}>
                  <span>{label}</span>
                  <input
                    type={isNum ? 'number' : path === 'ui.accent' ? 'text' : 'text'}
                    value={cur ?? (sparse ? '' : def)}
                    placeholder={sparse ? `Nasleđeno: ${inh === '' ? 'prazno' : inh}` : undefined}
                    onChange={(e) => {
                      const raw = e.target.value;
                      if (sparse && raw === '') return setField(g, k, undefined);
                      setField(g, k, isNum ? Number(raw) : raw);
                    }}
                  />
                </label>
              );
            })}
          </div>
        </fieldset>
      ))}
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        {sparse && (
          <button type="button" className="btn secondary" onClick={() => setV({})}>
            Vrati sve na nasleđeno
          </button>
        )}
        <button className="btn" disabled={busy}>
          {busy ? 'Čuvam…' : 'Sačuvaj podešavanja'}
        </button>
      </div>
    </form>
  );
}

function deepMerge(a: Obj, b: Obj): Obj {
  const out: Obj = structuredClone(a);
  for (const [k, val] of Object.entries(b ?? {})) {
    out[k] = val && typeof val === 'object' && !Array.isArray(val) ? deepMerge(out[k] ?? {}, val) : val;
  }
  return out;
}

export function SettingsPage() {
  const { data, reload } = useLoad<any>('/api/admin/settings');
  const toast = useToast();
  if (!data) return null;
  return (
    <>
      <PageHead title="Podešavanja aplikacije" sub="Važe za sve uređaje. Korisnik i pojedinačni uređaj mogu imati svoja podešavanja koja imaju prednost." />
      <div className="box">
        <SettingsEditor
          key={JSON.stringify(data.global)}
          value={deepMerge(data.defaults, data.global)}
          onSave={async (v) => {
            await put('/api/admin/settings', { settings: v });
            toast('Podešavanja su sačuvana i poslata uređajima');
            reload();
          }}
        />
      </div>
      <div className="box">
        <h2>Sajt za korisnike</h2>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            await put('/api/admin/settings/site', {
              companyName: String(f.get('companyName') ?? ''),
              supportContact: String(f.get('supportContact') ?? ''),
              announcement: String(f.get('announcement') ?? ''),
            });
            toast('Sačuvano');
            reload();
          }}
        >
          <div className="grid2">
            <label className="field">
              <span>Naziv firme (u podnožju)</span>
              <input name="companyName" defaultValue={data.site.companyName ?? ''} />
            </label>
            <label className="field">
              <span>Kontakt podrške</span>
              <input name="supportContact" defaultValue={data.site.supportContact ?? ''} placeholder="e-mail ili telefon" />
            </label>
          </div>
          <label className="field">
            <span>Obaveštenje na vrhu sajta</span>
            <input name="announcement" defaultValue={data.site.announcement ?? ''} placeholder="Prazno = bez obaveštenja" />
          </label>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button className="btn">Sačuvaj</button>
          </div>
        </form>
      </div>
    </>
  );
}

// ---------- Launcher i kiosk ----------

const LEVEL_TEXT: Record<string, string> = {
  auto: 'Automatski — najjači nivo koji uređaj podržava',
  launcher: 'Launcher (nivo 1–2) — aplikacija je početni ekran',
  device_owner: 'Pravi kiosk (nivo 3) — samo na pripremljenim uređajima',
};

export function Launchers() {
  const { data } = useLoad<any>('/api/admin/launcher-profiles');
  const nav = useNavigate();
  const { admin } = useAuth();
  return (
    <>
      <PageHead title="Launcher i kiosk" sub="Profil određuje da li je aplikacija početni ekran uređaja, koje aplikacije su dozvoljene i šta je zaključano.">
        {admin.role !== 'support' && (
          <button className="btn" onClick={() => nav('/admin/launchers/new')}>
            Novi profil
          </button>
        )}
      </PageHead>
      <div className="box small">
        <p style={{ margin: 0 }}>
          <strong>Nivo 1</strong> — korisnik bira aplikaciju kao početni ekran. <strong>Nivo 2</strong> — instalater jednom isključi sistemski launcher preko ADB-a.{' '}
          <strong>Nivo 3</strong> — uređaj je fabrički resetovan i aplikacija je postavljena kao Device Owner; tada rade blokada podešavanja, instalacije i statusne trake. Uputstvo je u repozitorijumu, u fajlu docs/KIOSK.md.
        </p>
      </div>
      <div className="table-wrap">
        {data && data.items.length === 0 ? (
          <div className="empty">Nema profila. Bez profila aplikacija radi kao obična aplikacija.</div>
        ) : (
          <table className="t">
            <thead>
              <tr>
                <th>Naziv</th>
                <th>Launcher</th>
                <th>Nivo</th>
                <th>Dozvoljene aplikacije</th>
                <th>Uređaja</th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((l: any) => (
                <tr key={l.id} className="click" onClick={() => nav(`/admin/launchers/${l.id}`)}>
                  <td>
                    <strong>{l.name}</strong> {l.global && admin.role === 'reseller' && <span className="tag">globalni</span>}
                  </td>
                  <td>
                    {l.config.enabled ? <span className="tag ok">uključen</span> : <span className="tag">isključen</span>}{' '}
                    {l.config.locked && <span className="tag warn">zaključan</span>}
                  </td>
                  <td className="small">{LEVEL_TEXT[l.config.kioskLevel]?.split(' — ')[0]}</td>
                  <td className="small">{l.config.allowedApps.map((a: any) => a.label || a.package).join(', ') || '—'}</td>
                  <td className="num">{l.deviceCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}

const COMMON_APPS: [string, string][] = [
  ['com.google.android.youtube.tv', 'YouTube (TV)'],
  ['com.google.android.youtube', 'YouTube (telefon)'],
  ['com.netflix.ninja', 'Netflix (TV)'],
  ['com.amazon.amazonvideo.livingroom', 'Prime Video (TV)'],
  ['com.disney.disneyplus', 'Disney+'],
  ['com.spotify.tv.android', 'Spotify (TV)'],
  ['com.android.chrome', 'Chrome'],
];

const EMPTY_CFG = {
  enabled: true,
  locked: false,
  kioskLevel: 'auto',
  allowedApps: [] as { package: string; label: string }[],
  bootTarget: '',
  blockSettings: false,
  showSystemSettingsTile: true,
  blockInstallUninstall: false,
  hideStatusBar: false,
  hasExitPin: false,
  branding: { title: '', logoUrl: '', backgroundUrl: '', accent: '', welcomeText: '' },
};

export function LauncherEdit() {
  const { id } = useParams();
  const isNew = id === 'new';
  const { data } = useLoad<any>(isNew ? null : `/api/admin/launcher-profiles/${id}`);
  const nav = useNavigate();
  const toast = useToast();
  const { admin } = useAuth();
  const [name, setName] = useState('');
  const [cfg, setCfg] = useState<any>(isNew ? structuredClone(EMPTY_CFG) : null);
  const [pin, setPin] = useState('');
  const [clearPin, setClearPin] = useState(false);
  const [newPkg, setNewPkg] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [confirmDel, setConfirmDel] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (data) {
      setName(data.launcher.name);
      setCfg({ ...structuredClone(EMPTY_CFG), ...data.launcher.config });
    }
  }, [data]);
  if (!cfg) return null;
  const readOnly = admin.role === 'support' || (admin.role === 'reseller' && data?.launcher.global);
  const set = (k: string, v: any) => setCfg((o: any) => ({ ...o, [k]: v }));
  const setB = (k: string, v: any) => setCfg((o: any) => ({ ...o, branding: { ...o.branding, [k]: v } }));
  const addApp = (pkg: string, label: string) => {
    if (!pkg.trim() || cfg.allowedApps.some((a: any) => a.package === pkg.trim())) return;
    set('allowedApps', [...cfg.allowedApps, { package: pkg.trim(), label: label.trim() }]);
  };
  const move = (i: number, d: number) => {
    const arr = cfg.allowedApps.slice();
    const [x] = arr.splice(i, 1);
    arr.splice(i + d, 0, x);
    set('allowedApps', arr);
  };

  return (
    <>
      <PageHead title={isNew ? 'Novi launcher profil' : name} />
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setError('');
          const { hasExitPin, ...rest } = cfg;
          const config: any = { ...rest };
          if (pin) config.exitPin = pin;
          if (clearPin) config.clearExitPin = true;
          try {
            if (isNew) {
              const r = await post('/api/admin/launcher-profiles', { name, config });
              toast('Profil je napravljen');
              nav(`/admin/launchers/${r.launcher.id}`, { replace: true });
            } else {
              const r = await put(`/api/admin/launcher-profiles/${id}`, { name, config });
              setCfg({ ...structuredClone(EMPTY_CFG), ...r.launcher.config });
              setPin('');
              setClearPin(false);
              toast('Sačuvano i poslato uređajima');
            }
          } catch (err: any) {
            setError(err.message);
          }
        }}
      >
        <fieldset disabled={readOnly} style={{ border: 0, padding: 0, margin: 0 }}>
          <div className="cols">
            <div>
              <div className="box">
                <h2>Osnovno</h2>
                <label className="field">
                  <span>Naziv profila</span>
                  <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} placeholder="npr. Hotel — sobe" />
                </label>
                <label className="check">
                  <input type="checkbox" checked={cfg.enabled} onChange={(e) => set('enabled', e.target.checked)} />
                  <span>
                    Aplikacija je početni ekran (launcher)
                    <small>Pokreće se na dugme Home i pri paljenju uređaja.</small>
                  </span>
                </label>
                <label className="check">
                  <input type="checkbox" checked={cfg.locked} onChange={(e) => set('locked', e.target.checked)} />
                  <span>
                    Zaključaj
                    <small>Korisnik u aplikaciji ne vidi opciju da isključi launcher.</small>
                  </span>
                </label>
                <label className="field">
                  <span>Nivo zaštite</span>
                  <select value={cfg.kioskLevel} onChange={(e) => set('kioskLevel', e.target.value)}>
                    {Object.entries(LEVEL_TEXT).map(([k, t]) => (
                      <option key={k} value={k}>
                        {t}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Pri paljenju otvori</span>
                  <select
                    value={['', 'live', 'last_channel'].includes(cfg.bootTarget) ? cfg.bootTarget : '__app'}
                    onChange={(e) => set('bootTarget', e.target.value === '__app' ? cfg.allowedApps[0]?.package ?? '' : e.target.value)}
                  >
                    <option value="">Početni ekran</option>
                    <option value="live">Live TV</option>
                    <option value="last_channel">Poslednji gledani kanal</option>
                    <option value="__app">Jednu od dozvoljenih aplikacija</option>
                  </select>
                  {!['', 'live', 'last_channel'].includes(cfg.bootTarget) && (
                    <select value={cfg.bootTarget} onChange={(e) => set('bootTarget', e.target.value)} style={{ marginTop: 6 }}>
                      {cfg.allowedApps.map((a: any) => (
                        <option key={a.package} value={a.package}>
                          {a.label || a.package}
                        </option>
                      ))}
                    </select>
                  )}
                </label>
              </div>

              <div className="box">
                <h2>Zaključavanje</h2>
                <p className="small muted">Pun efekat ovih opcija postoji samo na nivou 3. Na nivoima 1–2 aplikacija samo sakriva prečice.</p>
                <label className="check">
                  <input type="checkbox" checked={cfg.blockSettings} onChange={(e) => set('blockSettings', e.target.checked)} />
                  <span>Blokiraj sistemska podešavanja</span>
                </label>
                <label className="check">
                  <input type="checkbox" checked={cfg.showSystemSettingsTile} onChange={(e) => set('showSystemSettingsTile', e.target.checked)} />
                  <span>Prikaži pločicu „Podešavanja uređaja” na početnom ekranu</span>
                </label>
                <label className="check">
                  <input type="checkbox" checked={cfg.blockInstallUninstall} onChange={(e) => set('blockInstallUninstall', e.target.checked)} />
                  <span>Blokiraj instaliranje i brisanje aplikacija</span>
                </label>
                <label className="check">
                  <input type="checkbox" checked={cfg.hideStatusBar} onChange={(e) => set('hideStatusBar', e.target.checked)} />
                  <span>Sakrij statusnu traku i obaveštenja</span>
                </label>
                <label className="field">
                  <span>PIN za izlaz iz kiosk moda na uređaju</span>
                  <input value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 8))} inputMode="numeric" placeholder={cfg.hasExitPin ? 'PIN je postavljen — upišite novi za promenu' : '4–8 cifara'} />
                  <small>Serviser na daljinskom pritisne Meni 5 puta zaredom i unese PIN.</small>
                </label>
                {cfg.hasExitPin && (
                  <label className="check">
                    <input type="checkbox" checked={clearPin} onChange={(e) => setClearPin(e.target.checked)} />
                    <span>Ukloni postojeći PIN</span>
                  </label>
                )}
              </div>
            </div>

            <div>
              <div className="box">
                <h2>Dozvoljene aplikacije</h2>
                <p className="small muted">Prikazuju se na početnom ekranu ovim redosledom. Ostale aplikacije nisu vidljive.</p>
                {cfg.allowedApps.length > 0 && (
                  <table className="t" style={{ marginBottom: 12 }}>
                    <tbody>
                      {cfg.allowedApps.map((a: any, i: number) => (
                        <tr key={a.package}>
                          <td>
                            <input
                              value={a.label}
                              placeholder="Naziv"
                              onChange={(e) => set('allowedApps', cfg.allowedApps.map((x: any, j: number) => (j === i ? { ...x, label: e.target.value } : x)))}
                            />
                            <div className="small muted" style={{ marginTop: 4 }}>
                              {a.package}
                            </div>
                          </td>
                          <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                            <button type="button" className="btn ghost small" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Gore">
                              ↑
                            </button>
                            <button type="button" className="btn ghost small" disabled={i === cfg.allowedApps.length - 1} onClick={() => move(i, 1)} aria-label="Dole">
                              ↓
                            </button>
                            <button
                              type="button"
                              className="btn ghost small"
                              style={{ color: 'var(--bad)' }}
                              onClick={() => set('allowedApps', cfg.allowedApps.filter((_: any, j: number) => j !== i))}
                            >
                              Ukloni
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                <div className="row" style={{ marginBottom: 10 }}>
                  {COMMON_APPS.filter(([p]) => !cfg.allowedApps.some((a: any) => a.package === p)).map(([p, l]) => (
                    <button type="button" key={p} className="btn secondary small" onClick={() => addApp(p, l)}>
                      + {l}
                    </button>
                  ))}
                </div>
                <div className="row" style={{ flexWrap: 'nowrap' }}>
                  <input value={newPkg} onChange={(e) => setNewPkg(e.target.value)} placeholder="naziv paketa, npr. com.primer.app" />
                  <input value={newLabel} onChange={(e) => setNewLabel(e.target.value)} placeholder="Naziv" style={{ maxWidth: 140 }} />
                  <button
                    type="button"
                    className="btn secondary"
                    onClick={() => {
                      addApp(newPkg, newLabel);
                      setNewPkg('');
                      setNewLabel('');
                    }}
                  >
                    Dodaj
                  </button>
                </div>
                <p className="small muted" style={{ marginTop: 8 }}>
                  Naziv paketa vidite u detaljima uređaja, u listi instaliranih aplikacija koju uređaj šalje posle dijagnostike.
                </p>
              </div>

              <div className="box">
                <h2>Izgled početnog ekrana</h2>
                <div className="grid2">
                  <label className="field">
                    <span>Naslov</span>
                    <input value={cfg.branding.title} onChange={(e) => setB('title', e.target.value)} maxLength={60} placeholder="npr. Hotel Panorama" />
                  </label>
                  <label className="field">
                    <span>Boja akcenta</span>
                    <input value={cfg.branding.accent} onChange={(e) => setB('accent', e.target.value)} placeholder="#3D8BFF" />
                  </label>
                </div>
                <label className="field">
                  <span>Adresa logotipa (PNG ili SVG)</span>
                  <input value={cfg.branding.logoUrl} onChange={(e) => setB('logoUrl', e.target.value)} placeholder="https://…" />
                </label>
                <label className="field">
                  <span>Adresa pozadinske slike</span>
                  <input value={cfg.branding.backgroundUrl} onChange={(e) => setB('backgroundUrl', e.target.value)} placeholder="https://…" />
                </label>
                <label className="field">
                  <span>Pozdravni tekst</span>
                  <input value={cfg.branding.welcomeText} onChange={(e) => setB('welcomeText', e.target.value)} maxLength={300} placeholder="npr. Dobro došli! Wi-Fi lozinka je na recepciji." />
                </label>
              </div>
            </div>
          </div>
          {error && <div className="notice bad">{error}</div>}
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              {!isNew && !readOnly && (
                <button type="button" className="btn danger" onClick={() => setConfirmDel(true)}>
                  Obriši profil
                </button>
              )}
            </div>
            <div className="row">
              <button type="button" className="btn secondary" onClick={() => nav('/admin/launchers')}>
                Nazad
              </button>
              {!readOnly && <button className="btn">{isNew ? 'Napravi profil' : 'Sačuvaj'}</button>}
            </div>
          </div>
        </fieldset>
      </form>
      {confirmDel && (
        <Confirm
          text="Uređaji sa ovim profilom vraćaju se na običan rad aplikacije."
          confirmLabel="Obriši"
          danger
          onClose={() => setConfirmDel(false)}
          onConfirm={async () => {
            await del(`/api/admin/launcher-profiles/${id}`);
            toast('Profil je obrisan');
            nav('/admin/launchers');
          }}
        />
      )}
    </>
  );
}
