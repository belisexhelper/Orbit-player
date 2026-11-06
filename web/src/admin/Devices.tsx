import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { del, formatDate, patch, post, put, timeAgo } from '../api';
import { Confirm, Modal, useToast } from '../components/ui';
import { PlaylistForm } from '../components/PlaylistForm';
import { can, PageHead, useAuth, useLoad } from './Admin';
import { SettingsEditor } from './Settings';

export function Dashboard() {
  const { data } = useLoad<any>('/api/admin/dashboard');
  useAutoReload(data ? 30_000 : 0);
  return (
    <>
      <PageHead title="Pregled" sub="Stanje uređaja i korisnika" />
      {data && (
        <>
          <div className="stats">
            <Stat n={data.devices.online} label="uređaja trenutno na mreži" />
            <Stat n={data.devices.seen24h} label="aktivno u poslednja 24 h" />
            <Stat n={data.devices.total} label="uređaja ukupno" />
            <Stat n={data.devices.new7d} label="novih za 7 dana" />
          </div>
          <div className="stats">
            <Stat n={data.profiles.total} label="korisnika" />
            <Stat n={data.profiles.expiring7d} label="ističe za 7 dana" link="/admin/profiles?filter=expiring" />
            <Stat n={data.profiles.expired} label="isteklo" link="/admin/profiles?filter=expired" />
            <Stat n={data.errors24h} label="grešaka sa uređaja za 24 h" />
          </div>
          <div className="cols">
            <div className="box">
              <h2>Verzije aplikacije u upotrebi</h2>
              {data.versions.length ? (
                <table className="t">
                  <thead>
                    <tr>
                      <th>Verzija</th>
                      <th>Izdanje</th>
                      <th>Uređaja</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.versions.map((v: any) => (
                      <tr key={v.version + v.flavor}>
                        <td>{v.version || '—'}</td>
                        <td>{v.flavor}</td>
                        <td className="num">{v.n}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="muted">Još nema aktivnih uređaja.</p>
              )}
            </div>
            <div className="box">
              <h2>Brze radnje</h2>
              <div className="row">
                <Link className="btn" to="/admin/profiles?new=1">
                  Novi korisnik
                </Link>
                <Link className="btn secondary" to="/admin/devices?claim=1">
                  Poveži uređaj kodom sa TV-a
                </Link>
              </div>
              {data.devices.unassigned > 0 && (
                <p className="small muted" style={{ marginTop: 14 }}>
                  {data.devices.unassigned} uređaja nije dodeljeno nijednom korisniku (koriste svoje liste unete preko sajta).
                </p>
              )}
            </div>
          </div>
        </>
      )}
    </>
  );
}

function Stat({ n, label, link }: { n: number; label: string; link?: string }) {
  const body = (
    <>
      <b>{n ?? 0}</b>
      <span>{label}</span>
    </>
  );
  return link ? (
    <Link to={link} className="stat" style={{ textDecoration: 'none', color: 'inherit' }}>
      {body}
    </Link>
  ) : (
    <div className="stat">{body}</div>
  );
}

function useAutoReload(ms: number) {
  const [, set] = useState(0);
  useEffect(() => {
    if (!ms) return;
    const t = setInterval(() => set((x) => x + 1), ms);
    return () => clearInterval(t);
  }, [ms]);
}

export function OnlineDot({ online }: { online: boolean }) {
  return <span className={`dot${online ? ' on' : ''}`} title={online ? 'Na mreži' : 'Van mreže'} />;
}

export function DeviceName({ d }: { d: any }) {
  return (
    <>
      <strong>{d.name || `${d.manufacturer} ${d.model}`.trim() || 'Uređaj'}</strong>
      <div className="small muted">
        {d.isTv ? 'TV' : 'Telefon/tablet'} · Android {d.androidVersion || '?'} · v{d.appVersionName || '?'}
      </div>
    </>
  );
}

// ---------- Lista uređaja ----------

export function Devices() {
  const params = new URLSearchParams(window.location.search);
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('all');
  const [claim, setClaim] = useState(params.get('claim') === '1');
  const [search, setSearch] = useState('');
  const { admin } = useAuth();
  const nav = useNavigate();
  useEffect(() => {
    const t = setTimeout(() => setSearch(q), 300);
    return () => clearTimeout(t);
  }, [q]);
  const { data, reload } = useLoad<any>(`/api/admin/devices?status=${status}&q=${encodeURIComponent(search)}`);
  useEffect(() => {
    const t = setInterval(reload, 20_000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <PageHead title="Uređaji" sub={data ? `${data.total} uređaja` : undefined}>
        {can(admin, 'super', 'reseller') && (
          <button className="btn" onClick={() => setClaim(true)}>
            Poveži uređaj kodom sa TV-a
          </button>
        )}
      </PageHead>
      <div className="toolbar">
        <input placeholder="Pretraga: naziv, model, IP, korisnik" value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={status} onChange={(e) => setStatus(e.target.value)} style={{ minWidth: 0 }}>
          <option value="all">Svi</option>
          <option value="online">Na mreži</option>
          <option value="offline">Van mreže</option>
          <option value="unassigned">Bez korisnika</option>
          <option value="blocked">Blokirani</option>
        </select>
      </div>
      <div className="table-wrap">
        {data && data.items.length === 0 ? (
          <div className="empty">Nema uređaja. Uređaj se pojavljuje ovde čim se aplikacija prvi put pokrene.</div>
        ) : (
          <table className="t">
            <thead>
              <tr>
                <th>Uređaj</th>
                <th>Korisnik</th>
                <th>Gleda</th>
                <th>Launcher</th>
                <th>Poslednji put</th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((d: any) => (
                <tr key={d.id} className="click" onClick={() => nav(`/admin/devices/${d.id}`)}>
                  <td>
                    <div className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start', gap: 4 }}>
                      <OnlineDot online={d.online} />
                      <div>
                        <DeviceName d={d} />
                      </div>
                    </div>
                  </td>
                  <td>{d.profileName ?? <span className="muted">—</span>}</td>
                  <td className="small">{d.online ? d.status?.nowPlaying ?? d.status?.screen ?? '—' : <span className="muted">—</span>}</td>
                  <td>{d.launcherName ? <span className="tag navy">{d.launcherName}</span> : <span className="muted">—</span>}</td>
                  <td className="small">
                    {d.blocked ? <span className="tag bad">Blokiran</span> : d.online ? 'sada' : timeAgo(d.lastSeenAt)}
                    <div className="muted">{d.lastIp}</div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {claim && <ClaimModal onClose={() => setClaim(false)} onDone={(id) => nav(`/admin/devices/${id}`)} />}
    </>
  );
}

function ClaimModal({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { data: profiles } = useLoad<any>('/api/admin/profiles?limit=500');
  const { data: launchers } = useLoad<any>('/api/admin/launcher-profiles');
  const [code, setCode] = useState('');
  const [profileId, setProfileId] = useState('');
  const [launcherId, setLauncherId] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  return (
    <Modal title="Poveži uređaj kodom sa TV-a" onClose={onClose}>
      <p className="muted small">Korisnik vam pročita kod od 8 cifara sa ekrana televizora. Uređaj se odmah povezuje sa izabranim korisnikom i preuzima njegove liste.</p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setError('');
          try {
            const r = await post('/api/admin/devices/claim', { code, profileId, launcherProfileId: launcherId || null, name });
            onDone(r.deviceId);
          } catch (err: any) {
            setError(err.message);
          }
        }}
      >
        <label className="field">
          <span>Kod sa ekrana</span>
          <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" placeholder="1234 5678" required autoFocus />
        </label>
        <label className="field">
          <span>Korisnik</span>
          <select value={profileId} onChange={(e) => setProfileId(e.target.value)} required>
            <option value="">Izaberite…</option>
            {profiles?.items.map((p: any) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
        <div className="grid2">
          <label className="field">
            <span>Naziv uređaja (nije obavezno)</span>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="npr. Dnevna soba" />
          </label>
          <label className="field">
            <span>Launcher profil</span>
            <select value={launcherId} onChange={(e) => setLauncherId(e.target.value)}>
              <option value="">Kao kod korisnika</option>
              {launchers?.items.map((l: any) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          </label>
        </div>
        {error && <div className="notice bad">{error}</div>}
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onClose}>
            Odustani
          </button>
          <button className="btn">Poveži</button>
        </div>
      </form>
    </Modal>
  );
}

// ---------- Detalj uređaja ----------

const COMMANDS: { type: string; label: string; roles?: string[]; note?: string }[] = [
  { type: 'reload_config', label: 'Osveži podešavanja' },
  { type: 'refresh_playlists', label: 'Osveži liste i TV vodič' },
  { type: 'run_diagnostics', label: 'Pokreni dijagnostiku' },
  { type: 'check_update', label: 'Proveri ažuriranje' },
  { type: 'go_home', label: 'Vrati na početni ekran' },
  { type: 'restart_app', label: 'Restartuj aplikaciju' },
  { type: 'clear_cache', label: 'Obriši keš' },
  { type: 'reboot', label: 'Restartuj uređaj', roles: ['super', 'reseller'], note: 'radi samo u kiosk nivou 3' },
  { type: 'exit_kiosk', label: 'Privremeno izađi iz kiosk moda', roles: ['super', 'reseller'] },
  { type: 'unpair', label: 'Odjavi uređaj i obriši podatke', roles: ['super', 'reseller'] },
];

const STATUS_LABEL: Record<string, string> = { pending: 'čeka', sent: 'poslato', done: 'izvršeno', failed: 'neuspešno', expired: 'isteklo' };
const COMMAND_LABEL: Record<string, string> = Object.fromEntries(COMMANDS.map((c) => [c.type, c.label]).concat([
  ['show_message', 'Poruka na ekranu'],
  ['play_channel', 'Pusti kanal'],
  ['open_app', 'Otvori aplikaciju'],
]));

export function DeviceDetail() {
  const { id } = useParams();
  const { admin } = useAuth();
  const toast = useToast();
  const nav = useNavigate();
  const { data, reload } = useLoad<any>(`/api/admin/devices/${id}`);
  const { data: profiles } = useLoad<any>('/api/admin/profiles?limit=500');
  const { data: launchers } = useLoad<any>('/api/admin/launcher-profiles');
  const [modal, setModal] = useState<'' | 'message' | 'playlist' | 'settings' | 'delete' | 'rename' | 'channel'>('');
  const [confirmCmd, setConfirmCmd] = useState<string | null>(null);
  useEffect(() => {
    const t = setInterval(reload, 10_000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  if (!data) return null;
  const d = data.device;
  const s = d.status ?? {};

  const send = async (type: string, payload: Record<string, unknown> = {}) => {
    const r = await post(`/api/admin/devices/${id}/commands`, { type, payload });
    toast(r.command.status === 'sent' ? 'Komanda je poslata uređaju' : 'Uređaj nije na mreži — komanda čeka da se poveže');
    reload();
  };
  const update = async (body: Record<string, unknown>, msg = 'Sačuvano') => {
    await patch(`/api/admin/devices/${id}`, body);
    toast(msg);
    reload();
  };

  return (
    <>
      <PageHead
        title={d.name || `${d.manufacturer} ${d.model}`}
        sub={
          <>
            <OnlineDot online={d.online} />
            {d.online ? 'Na mreži' : `Van mreže · viđen ${timeAgo(d.lastSeenAt)}`} · {d.lastIp}
          </>
        }
      >
        <button className="btn secondary" onClick={() => setModal('rename')}>
          Preimenuj
        </button>
        {can(admin, 'super', 'reseller') && (
          <button className={`btn ${d.blocked ? '' : 'danger'}`} onClick={() => update({ blocked: !d.blocked }, d.blocked ? 'Uređaj je odblokiran' : 'Uređaj je blokiran')}>
            {d.blocked ? 'Odblokiraj' : 'Blokiraj'}
          </button>
        )}
      </PageHead>

      {d.blocked && <div className="notice bad">Uređaj je blokiran: aplikacija ne prikazuje nijednu listu.</div>}

      <div className="cols">
        <div>
          <div className="box">
            <h2>Stanje uživo</h2>
            <dl className="kv">
              <dt>Ekran</dt>
              <dd>{s.screen ?? '—'}</dd>
              <dt>Gleda</dt>
              <dd>{s.nowPlaying ?? '—'}</dd>
              <dt>Nivo launchera</dt>
              <dd>{launcherLevelLabel(s.launcherLevel)}</dd>
              <dt>Mreža</dt>
              <dd>{s.network ?? '—'}</dd>
              <dt>Memorija</dt>
              <dd>{s.freeStorageMb != null ? `${s.freeStorageMb} MB slobodno` : '—'}{s.ramMb != null ? ` · RAM ${s.ramMb} MB` : ''}</dd>
              <dt>Uređaj</dt>
              <dd>
                {d.manufacturer} {d.model} · Android {d.androidVersion} (SDK {d.sdkInt})
              </dd>
              <dt>Aplikacija</dt>
              <dd>
                {d.flavor} {d.appVersionName} ({d.appVersionCode})
              </dd>
              <dt>Registrovan</dt>
              <dd>{formatDate(d.createdAt)}</dd>
              <dt>ID</dt>
              <dd className="small muted">{d.id}</dd>
            </dl>
          </div>

          <div className="box">
            <h2>Daljinske komande</h2>
            <div className="row">
              <button className="btn small" onClick={() => setModal('message')}>
                Pošalji poruku na ekran
              </button>
              <button className="btn small secondary" onClick={() => setModal('channel')}>
                Pusti kanal / aplikaciju
              </button>
              {COMMANDS.filter((c) => !c.roles || c.roles.includes(admin.role)).map((c) => (
                <button
                  key={c.type}
                  className={`btn small ${['unpair', 'reboot', 'exit_kiosk'].includes(c.type) ? 'danger' : 'secondary'}`}
                  title={c.note}
                  onClick={() => (['unpair', 'reboot', 'exit_kiosk', 'clear_cache'].includes(c.type) ? setConfirmCmd(c.type) : void send(c.type))}
                >
                  {c.label}
                </button>
              ))}
            </div>
          </div>

          <div className="box">
            <h2>Istorija komandi</h2>
            {data.commands.length === 0 ? (
              <p className="muted">Još nije poslata nijedna komanda.</p>
            ) : (
              <table className="t">
                <tbody>
                  {data.commands.map((c: any) => (
                    <tr key={c.id}>
                      <td>
                        {COMMAND_LABEL[c.type] ?? c.type}
                        {c.payload?.text && <div className="small muted">„{c.payload.text}”</div>}
                        {c.result && c.type === 'run_diagnostics' && <pre className="json">{JSON.stringify(c.result, null, 2)}</pre>}
                      </td>
                      <td>
                        <span className={`tag ${c.status === 'done' ? 'ok' : c.status === 'failed' ? 'bad' : c.status === 'expired' ? '' : 'warn'}`}>{STATUS_LABEL[c.status]}</span>
                      </td>
                      <td className="small muted">{formatDate(c.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        <div>
          <div className="box">
            <h2>Korisnik i launcher</h2>
            <label className="field">
              <span>Korisnik</span>
              <select
                value={d.profileId ?? ''}
                disabled={!can(admin, 'super', 'reseller')}
                onChange={(e) => update({ profileId: e.target.value || null }, 'Korisnik je promenjen')}
              >
                <option value="">Bez korisnika (samo liste uređaja)</option>
                {profiles?.items.map((p: any) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              {data.profile && (
                <small>
                  <Link to={`/admin/profiles/${data.profile.id}`}>Otvori korisnika</Link> · pretplata do {formatDate(data.profile.expiresAt, false)}
                </small>
              )}
            </label>
            <label className="field">
              <span>Launcher profil</span>
              <select value={d.launcherProfileId ?? ''} onChange={(e) => update({ launcherProfileId: e.target.value || null }, 'Launcher profil je promenjen')}>
                <option value="">Kao kod korisnika</option>
                {launchers?.items.map((l: any) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
            </label>
            <button className="btn secondary small" onClick={() => setModal('settings')}>
              Posebna podešavanja za ovaj uređaj{Object.keys(d.settings ?? {}).length ? ' (postavljeno)' : ''}
            </button>
          </div>

          <div className="box">
            <h2>
              Liste uređaja
              <button className="btn small" onClick={() => setModal('playlist')}>
                Dodaj listu
              </button>
            </h2>
            <p className="small muted">Liste koje je korisnik uneo preko sajta ili ste ih dodali samo ovom uređaju. Liste korisnika se dodaju na stranici korisnika.</p>
            <PlaylistTable items={data.playlists} onChanged={reload} />
          </div>

          <div className="box">
            <h2>Dnevnik uređaja</h2>
            {data.logs.length === 0 ? (
              <p className="muted">Nema zapisa.</p>
            ) : (
              <div style={{ maxHeight: 420, overflowY: 'auto' }}>
                {data.logs.map((l: any) => (
                  <details key={l.id} style={{ borderBottom: '1px solid var(--line-2)', padding: '6px 0' }}>
                    <summary className="small">
                      <span className={`tag ${l.level === 'error' || l.level === 'crash' ? 'bad' : l.level === 'warn' ? 'warn' : ''}`}>{l.level}</span> {l.message.slice(0, 120)}{' '}
                      <span className="muted">{formatDate(l.createdAt)}</span>
                    </summary>
                    <pre className="json">{l.message}{l.data ? `\n\n${JSON.stringify(l.data, null, 2)}` : ''}</pre>
                  </details>
                ))}
              </div>
            )}
          </div>

          {admin.role === 'super' && (
            <button className="btn danger" onClick={() => setModal('delete')}>
              Obriši uređaj
            </button>
          )}
        </div>
      </div>

      {modal === 'message' && <MessageModal onClose={() => setModal('')} onSend={(text, durationSec) => send('show_message', { text, durationSec })} />}
      {modal === 'channel' && (
        <ChannelModal
          onClose={() => setModal('')}
          onSend={async (kind, value) => {
            if (kind === 'channel') await send('play_channel', /^\d+$/.test(value) ? { number: Number(value) } : { name: value });
            else await send('open_app', { package: value });
          }}
        />
      )}
      {modal === 'rename' && (
        <Modal title="Naziv uređaja" onClose={() => setModal('')}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              await update({ name: String(new FormData(e.currentTarget).get('name') ?? '') });
              setModal('');
            }}
          >
            <label className="field">
              <span>Naziv</span>
              <input name="name" defaultValue={d.name} maxLength={80} autoFocus />
            </label>
            <div className="actions">
              <button type="button" className="btn secondary" onClick={() => setModal('')}>
                Odustani
              </button>
              <button className="btn">Sačuvaj</button>
            </div>
          </form>
        </Modal>
      )}
      {modal === 'playlist' && (
        <Modal title="Nova lista za ovaj uređaj" onClose={() => setModal('')}>
          <PlaylistForm
            submitLabel="Dodaj listu"
            onTest={(p) => post('/api/admin/playlists/test', { playlist: p })}
            onCancel={() => setModal('')}
            onSubmit={async (p) => {
              await post(`/api/admin/devices/${id}/playlists`, { playlist: p });
              toast('Lista je dodata i poslata uređaju');
              setModal('');
              reload();
            }}
          />
        </Modal>
      )}
      {modal === 'settings' && (
        <Modal title="Podešavanja samo za ovaj uređaj" onClose={() => setModal('')} wide>
          <p className="small muted">Popunjena polja imaju prednost nad podešavanjima korisnika i globalnim podešavanjima.</p>
          <SettingsEditor
            value={d.settings ?? {}}
            sparse
            onSave={async (v) => {
              await update({ settings: v }, 'Podešavanja uređaja su sačuvana');
              setModal('');
            }}
          />
        </Modal>
      )}
      {modal === 'delete' && (
        <Confirm
          text="Uređaj će biti odjavljen i obrisan zajedno sa svojim listama. Ako se aplikacija ponovo pokrene, pojaviće se kao novi uređaj."
          confirmLabel="Obriši uređaj"
          danger
          onClose={() => setModal('')}
          onConfirm={async () => {
            await del(`/api/admin/devices/${id}`);
            toast('Uređaj je obrisan');
            nav('/admin/devices');
          }}
        />
      )}
      {confirmCmd && (
        <Confirm
          text={`Poslati komandu „${COMMAND_LABEL[confirmCmd]}”?`}
          confirmLabel="Pošalji"
          danger={confirmCmd !== 'clear_cache'}
          onClose={() => setConfirmCmd(null)}
          onConfirm={() => send(confirmCmd)}
        />
      )}
    </>
  );
}

export function launcherLevelLabel(level: unknown) {
  switch (level) {
    case 'device_owner':
      return 'Nivo 3 — pravi kiosk (Device Owner)';
    case 'default_home':
      return 'Nivo 1–2 — podrazumevani launcher';
    case 'not_default':
      return 'Launcher uključen, ali nije podrazumevani na uređaju';
    case 'off':
      return 'Isključen';
    default:
      return '—';
  }
}

export function PlaylistTable({ items, onChanged }: { items: any[]; onChanged: () => void }) {
  const [editing, setEditing] = useState<any>(null);
  const [removing, setRemoving] = useState<any>(null);
  const toast = useToast();
  if (!items.length) return <p className="muted">Nema lista.</p>;
  return (
    <>
      <table className="t">
        <tbody>
          {items.map((p) => (
            <tr key={p.id}>
              <td>
                <strong>{p.name}</strong> <span className="tag">{p.type === 'xtream' ? 'Xtream' : 'M3U'}</span> {p.source === 'web' && <span className="tag navy">uneto preko sajta</span>}
                <div className="small muted" style={{ wordBreak: 'break-all' }}>
                  {p.type === 'xtream' ? `${p.server} · ${p.username}` : p.url}
                </div>
              </td>
              <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                <button className="btn ghost small" onClick={() => setEditing(p)}>
                  Izmeni
                </button>
                <button className="btn ghost small" style={{ color: 'var(--bad)' }} onClick={() => setRemoving(p)}>
                  Ukloni
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing && (
        <Modal title="Izmena liste" onClose={() => setEditing(null)}>
          <PlaylistForm
            editing
            initial={editing}
            submitLabel="Sačuvaj"
            onTest={(p) => post('/api/admin/playlists/test', { playlist: p, existingId: editing.id })}
            onCancel={() => setEditing(null)}
            onSubmit={async (p) => {
              await put(`/api/admin/playlists/${editing.id}`, { playlist: p });
              toast('Lista je sačuvana');
              setEditing(null);
              onChanged();
            }}
          />
        </Modal>
      )}
      {removing && (
        <Confirm
          text={`Ukloniti listu „${removing.name}”? Uređaji je odmah gube.`}
          confirmLabel="Ukloni"
          danger
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            await del(`/api/admin/playlists/${removing.id}`);
            toast('Lista je uklonjena');
            onChanged();
          }}
        />
      )}
    </>
  );
}

export function MessageModal({ onClose, onSend }: { onClose: () => void; onSend: (text: string, durationSec: number) => Promise<void> }) {
  const [text, setText] = useState('');
  const [dur, setDur] = useState(15);
  return (
    <Modal title="Poruka na ekranu" onClose={onClose}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          await onSend(text, dur);
          onClose();
        }}
      >
        <label className="field">
          <span>Tekst poruke</span>
          <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={500} required autoFocus />
        </label>
        <label className="field">
          <span>Koliko dugo se prikazuje</span>
          <select value={dur} onChange={(e) => setDur(Number(e.target.value))}>
            <option value={10}>10 sekundi</option>
            <option value={15}>15 sekundi</option>
            <option value={30}>30 sekundi</option>
            <option value={60}>1 minut</option>
            <option value={0}>Dok korisnik ne zatvori</option>
          </select>
        </label>
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onClose}>
            Odustani
          </button>
          <button className="btn">Pošalji</button>
        </div>
      </form>
    </Modal>
  );
}

function ChannelModal({ onClose, onSend }: { onClose: () => void; onSend: (kind: 'channel' | 'app', value: string) => Promise<void> }) {
  const [kind, setKind] = useState<'channel' | 'app'>('channel');
  const [value, setValue] = useState('');
  return (
    <Modal title="Pusti kanal ili otvori aplikaciju" onClose={onClose}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          await onSend(kind, value.trim());
          onClose();
        }}
      >
        <div className="seg" style={{ marginBottom: 14 }}>
          <button type="button" className={kind === 'channel' ? 'on' : ''} onClick={() => setKind('channel')}>
            Kanal
          </button>
          <button type="button" className={kind === 'app' ? 'on' : ''} onClick={() => setKind('app')}>
            Aplikacija
          </button>
        </div>
        <label className="field">
          <span>{kind === 'channel' ? 'Broj ili naziv kanala' : 'Naziv paketa aplikacije'}</span>
          <input value={value} onChange={(e) => setValue(e.target.value)} placeholder={kind === 'channel' ? 'npr. 12 ili RTS 1' : 'npr. com.google.android.youtube.tv'} required autoFocus />
        </label>
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onClose}>
            Odustani
          </button>
          <button className="btn">Pošalji</button>
        </div>
      </form>
    </Modal>
  );
}
