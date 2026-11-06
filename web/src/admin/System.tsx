import { useState } from 'react';
import QRCode from 'qrcode';
import { api, del, formatBytes, formatDate, patch, post } from '../api';
import { Confirm, Modal, useToast } from '../components/ui';
import { PageHead, ROLE_LABEL, useAuth, useLoad } from './Admin';

// ---------- Poruke na ekranu ----------

export function Messages() {
  const { admin } = useAuth();
  const toast = useToast();
  const { data: profiles } = useLoad<any>('/api/admin/profiles?limit=500');
  const [target, setTarget] = useState(admin.role === 'super' ? 'all' : '');
  const [text, setText] = useState('');
  const [dur, setDur] = useState(30);
  const [busy, setBusy] = useState(false);
  return (
    <>
      <PageHead title="Poruke na ekranu" sub="Poruka se prikazuje preko slike na televizoru. Uređaji van mreže je dobijaju kada se povežu (u naredna 24 h)." />
      <div className="box" style={{ maxWidth: 640 }}>
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            try {
              const body: any = { type: 'show_message', payload: { text, durationSec: dur } };
              if (target === 'all') body.all = true;
              else body.profileId = target;
              const r = await post('/api/admin/commands/bulk', body);
              toast(`Poslato na ${r.total} uređaja (odmah: ${r.deliveredNow})`);
              setText('');
            } catch (err: any) {
              toast(err.message, true);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label className="field">
            <span>Kome</span>
            <select value={target} onChange={(e) => setTarget(e.target.value)} required>
              {admin.role === 'super' && <option value="all">Svim uređajima aktivnim u poslednjih 30 dana</option>}
              {admin.role !== 'super' && <option value="">Izaberite korisnika…</option>}
              {profiles?.items.map((p: any) => (
                <option key={p.id} value={p.id}>
                  Uređajima korisnika: {p.name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Tekst</span>
            <textarea value={text} onChange={(e) => setText(e.target.value)} maxLength={500} required placeholder="npr. Večeras od 02:00 do 03:00 radovi na serveru." />
          </label>
          <label className="field">
            <span>Trajanje</span>
            <select value={dur} onChange={(e) => setDur(Number(e.target.value))}>
              <option value={15}>15 sekundi</option>
              <option value={30}>30 sekundi</option>
              <option value={60}>1 minut</option>
              <option value={0}>Dok korisnik ne zatvori</option>
            </select>
          </label>
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button className="btn" disabled={busy}>
              {busy ? 'Šaljem…' : 'Pošalji poruku'}
            </button>
          </div>
        </form>
      </div>
    </>
  );
}

// ---------- Verzije aplikacije ----------

export function Releases() {
  const { data, reload } = useLoad<any>('/api/admin/releases');
  const { admin } = useAuth();
  const toast = useToast();
  const [upload, setUpload] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [removing, setRemoving] = useState<any>(null);
  return (
    <>
      <PageHead title="Verzije aplikacije" sub="Uređaji proveravaju nove verzije pri pokretanju i jednom dnevno. Postepeno puštanje šalje verziju samo delu uređaja.">
        {admin.role === 'super' && (
          <button className="btn" onClick={() => setUpload(true)}>
            Objavi novu verziju
          </button>
        )}
      </PageHead>
      <div className="table-wrap">
        {data && data.items.length === 0 ? (
          <div className="empty">Još nema objavljenih verzija. APK fajlove pravi GitHub Actions — preuzmite ih sa stranice Actions i objavite ovde.</div>
        ) : (
          <table className="t">
            <thead>
              <tr>
                <th>Verzija</th>
                <th>Izdanje</th>
                <th>Kanal</th>
                <th>Puštanje</th>
                <th>Veličina</th>
                <th>Objavljeno</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {data?.items.map((r: any) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.versionName}</strong> <span className="muted small">({r.versionCode})</span> {r.mandatory && <span className="tag warn">obavezno</span>}
                    {r.changelog && <div className="small muted" style={{ whiteSpace: 'pre-line' }}>{r.changelog}</div>}
                  </td>
                  <td>{r.flavor === 'pro' ? 'Pro' : 'Lite'}</td>
                  <td>{r.channel === 'beta' ? <span className="tag warn">beta</span> : <span className="tag ok">stabilno</span>}</td>
                  <td className="num">{r.rolloutPercent}%</td>
                  <td className="num">{formatBytes(r.size)}</td>
                  <td className="small muted">{formatDate(r.createdAt)}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    {admin.role === 'super' && (
                      <>
                        <button className="btn ghost small" onClick={() => setEditing(r)}>
                          Izmeni
                        </button>
                        <button className="btn ghost small" style={{ color: 'var(--bad)' }} onClick={() => setRemoving(r)}>
                          Obriši
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {upload && <UploadModal onClose={() => setUpload(false)} onDone={() => (setUpload(false), reload(), toast('Verzija je objavljena'))} />}
      {editing && (
        <Modal title={`Verzija ${editing.versionName}`} onClose={() => setEditing(null)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              await patch(`/api/admin/releases/${editing.id}`, {
                channel: f.get('channel'),
                rolloutPercent: Number(f.get('rollout')),
                mandatory: f.get('mandatory') === 'on',
                changelog: String(f.get('changelog') ?? ''),
              });
              setEditing(null);
              reload();
              toast('Sačuvano');
            }}
          >
            <ReleaseFields r={editing} />
            <div className="actions">
              <button type="button" className="btn secondary" onClick={() => setEditing(null)}>
                Odustani
              </button>
              <button className="btn">Sačuvaj</button>
            </div>
          </form>
        </Modal>
      )}
      {removing && (
        <Confirm
          text={`Obrisati verziju ${removing.versionName}? Uređaji koji je već imaju ostaju na njoj.`}
          confirmLabel="Obriši"
          danger
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            await del(`/api/admin/releases/${removing.id}`);
            reload();
          }}
        />
      )}
    </>
  );
}

function ReleaseFields({ r }: { r?: any }) {
  return (
    <>
      <div className="grid2">
        <label className="field">
          <span>Kanal</span>
          <select name="channel" defaultValue={r?.channel ?? 'stable'}>
            <option value="stable">Stabilno — svi uređaji</option>
            <option value="beta">Beta — samo uređaji na beta kanalu</option>
          </select>
        </label>
        <label className="field">
          <span>Postepeno puštanje (% uređaja)</span>
          <input name="rollout" type="number" min={0} max={100} defaultValue={r?.rolloutPercent ?? 100} />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" name="mandatory" defaultChecked={r?.mandatory} />
        <span>
          Obavezno ažuriranje
          <small>Korisnik ne može da odloži instalaciju.</small>
        </span>
      </label>
      <label className="field">
        <span>Šta je novo</span>
        <textarea name="changelog" defaultValue={r?.changelog ?? ''} maxLength={5000} />
      </label>
    </>
  );
}

function UploadModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <Modal title="Objavi novu verziju" onClose={onClose}>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError('');
          const src = new FormData(e.currentTarget);
          const form = new FormData();
          // polja moraju ići pre fajla
          for (const k of ['flavor', 'versionCode', 'versionName', 'channel', 'changelog']) form.append(k, String(src.get(k) ?? ''));
          form.append('rolloutPercent', String(src.get('rollout') ?? '100'));
          form.append('mandatory', src.get('mandatory') === 'on' ? 'true' : 'false');
          form.append('file', src.get('file') as File);
          try {
            await api('POST', '/api/admin/releases', undefined, { form });
            onDone();
          } catch (err: any) {
            setError(err.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label className="field">
          <span>APK fajl</span>
          <input type="file" name="file" accept=".apk" required />
          <small>Ime fajla iz GitHub Actions sadrži verziju, npr. orbit-pro-0.2.0-12.apk → verzija 0.2.0, kod 12.</small>
        </label>
        <div className="grid2">
          <label className="field">
            <span>Izdanje</span>
            <select name="flavor" defaultValue="pro">
              <option value="pro">Pro (sa panelom)</option>
              <option value="lite">Lite</option>
            </select>
          </label>
          <label className="field">
            <span>Naziv verzije</span>
            <input name="versionName" required placeholder="0.2.0" />
          </label>
          <label className="field">
            <span>Kod verzije (versionCode)</span>
            <input name="versionCode" type="number" min={1} required placeholder="12" />
          </label>
        </div>
        <ReleaseFields />
        {error && <div className="notice bad">{error}</div>}
        <div className="actions">
          <button type="button" className="btn secondary" onClick={onClose}>
            Odustani
          </button>
          <button className="btn" disabled={busy}>
            {busy ? 'Otpremam…' : 'Objavi'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------- Admin nalozi ----------

export function Admins() {
  const { data, reload } = useLoad<any>('/api/admin/admins');
  const { admin } = useAuth();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState('');
  return (
    <>
      <PageHead title="Admin nalozi" sub="Podrška može da gleda i šalje komande. Reseler vidi samo svoje korisnike i njihove uređaje.">
        <button className="btn" onClick={() => setCreating(true)}>
          Novi nalog
        </button>
      </PageHead>
      <div className="table-wrap">
        <table className="t">
          <thead>
            <tr>
              <th>Korisničko ime</th>
              <th>Uloga</th>
              <th>2FA</th>
              <th>Poslednja prijava</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {data?.items.map((a: any) => (
              <tr key={a.id}>
                <td>
                  <strong>{a.username}</strong> {a.disabled && <span className="tag bad">isključen</span>}
                </td>
                <td>
                  <select
                    value={a.role}
                    disabled={a.id === admin.id}
                    onChange={async (e) => {
                      await patch(`/api/admin/admins/${a.id}`, { role: e.target.value });
                      reload();
                    }}
                    style={{ width: 'auto' }}
                  >
                    {Object.entries(ROLE_LABEL).map(([k, l]) => (
                      <option key={k} value={k}>
                        {l}
                      </option>
                    ))}
                  </select>
                </td>
                <td>{a.totpEnabled ? <span className="tag ok">uključeno</span> : <span className="tag">ne</span>}</td>
                <td className="small muted">{formatDate(a.lastLoginAt)}</td>
                <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                  {a.id !== admin.id && (
                    <>
                      <button
                        className="btn ghost small"
                        onClick={async () => {
                          const pw = window.prompt('Nova lozinka (bar 10 znakova):');
                          if (!pw) return;
                          try {
                            await patch(`/api/admin/admins/${a.id}`, { password: pw });
                            toast('Lozinka je promenjena');
                          } catch (e: any) {
                            toast(e.message, true);
                          }
                        }}
                      >
                        Nova lozinka
                      </button>
                      {a.totpEnabled && (
                        <button className="btn ghost small" onClick={async () => (await patch(`/api/admin/admins/${a.id}`, { resetTotp: true }), reload())}>
                          Resetuj 2FA
                        </button>
                      )}
                      <button className="btn ghost small" onClick={async () => (await patch(`/api/admin/admins/${a.id}`, { disabled: !a.disabled }), reload())}>
                        {a.disabled ? 'Uključi' : 'Isključi'}
                      </button>
                      <button
                        className="btn ghost small"
                        style={{ color: 'var(--bad)' }}
                        onClick={async () => {
                          if (!window.confirm(`Obrisati nalog ${a.username}?`)) return;
                          await del(`/api/admin/admins/${a.id}`);
                          reload();
                        }}
                      >
                        Obriši
                      </button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {creating && (
        <Modal title="Novi admin nalog" onClose={() => setCreating(false)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              setError('');
              try {
                await post('/api/admin/admins', { username: f.get('username'), password: f.get('password'), role: f.get('role') });
                setCreating(false);
                reload();
              } catch (err: any) {
                setError(err.message);
              }
            }}
          >
            <label className="field">
              <span>Korisničko ime</span>
              <input name="username" required minLength={3} autoComplete="off" />
            </label>
            <label className="field">
              <span>Lozinka</span>
              <input name="password" type="password" required minLength={10} autoComplete="new-password" />
            </label>
            <label className="field">
              <span>Uloga</span>
              <select name="role" defaultValue="support">
                {Object.entries(ROLE_LABEL).map(([k, l]) => (
                  <option key={k} value={k}>
                    {l}
                  </option>
                ))}
              </select>
            </label>
            {error && <div className="notice bad">{error}</div>}
            <div className="actions">
              <button type="button" className="btn secondary" onClick={() => setCreating(false)}>
                Odustani
              </button>
              <button className="btn">Napravi</button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}

// ---------- Istorija izmena ----------

const ACTION_LABEL: Record<string, string> = {
  login: 'Prijava',
  login_failed: 'Neuspešna prijava',
  profile_created: 'Napravljen korisnik',
  profile_updated: 'Izmenjen korisnik',
  profile_deleted: 'Obrisan korisnik',
  activation_code_created: 'Napravljen kod za aktivaciju',
  playlist_added: 'Dodata lista',
  playlist_updated: 'Izmenjena lista',
  playlist_deleted: 'Obrisana lista',
  playlist_revealed: 'Prikazana lozinka liste',
  device_updated: 'Izmenjen uređaj',
  device_deleted: 'Obrisan uređaj',
  device_claimed: 'Uređaj povezan kodom',
  command_sent: 'Poslata komanda',
  bulk_command: 'Komanda za više uređaja',
  launcher_created: 'Napravljen launcher profil',
  launcher_updated: 'Izmenjen launcher profil',
  launcher_deleted: 'Obrisan launcher profil',
  settings_updated: 'Izmenjena podešavanja',
  site_settings_updated: 'Izmenjena podešavanja sajta',
  release_uploaded: 'Objavljena verzija',
  release_updated: 'Izmenjena verzija',
  release_deleted: 'Obrisana verzija',
  admin_created: 'Napravljen admin',
  admin_updated: 'Izmenjen admin',
  admin_deleted: 'Obrisan admin',
  password_changed: 'Promenjena lozinka',
  totp_enabled: 'Uključena 2FA',
  totp_disabled: 'Isključena 2FA',
};

export function Audit() {
  const [q, setQ] = useState('');
  const { data } = useLoad<any>(`/api/admin/audit?limit=300${q ? `&q=${encodeURIComponent(q)}` : ''}`);
  return (
    <>
      <PageHead title="Istorija izmena" sub="Ko je šta promenio i kada." />
      <div className="toolbar">
        <input placeholder="Filter: admin, radnja ili ID" value={q} onChange={(e) => setQ(e.target.value)} />
      </div>
      <div className="table-wrap">
        <table className="t">
          <thead>
            <tr>
              <th>Vreme</th>
              <th>Admin</th>
              <th>Radnja</th>
              <th>Objekat</th>
              <th>IP</th>
            </tr>
          </thead>
          <tbody>
            {data?.items.map((r: any) => (
              <tr key={r.id}>
                <td className="small">{formatDate(r.createdAt)}</td>
                <td>{r.admin || '—'}</td>
                <td>
                  {ACTION_LABEL[r.action] ?? r.action}
                  {r.details && <div className="small muted" style={{ wordBreak: 'break-all' }}>{JSON.stringify(r.details).slice(0, 160)}</div>}
                </td>
                <td className="small muted" style={{ wordBreak: 'break-all' }}>{r.target}</td>
                <td className="small muted">{r.ip}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

// ---------- Moj nalog ----------

export function Account() {
  const { admin, refresh } = useAuth();
  const toast = useToast();
  const [setup, setSetup] = useState<{ secret: string; otpauthUrl: string; qr: string } | null>(null);
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  return (
    <>
      <PageHead title="Moj nalog" sub={`${admin.username} · ${ROLE_LABEL[admin.role]}`} />
      <div className="cols">
        <div className="box">
          <h2>Promena lozinke</h2>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const formEl = e.currentTarget;
              const f = new FormData(formEl);
              try {
                await post('/api/admin/auth/password', { current: f.get('current'), next: f.get('next') });
                toast('Lozinka je promenjena. Ostale sesije su odjavljene.');
                formEl.reset();
              } catch (err: any) {
                toast(err.message, true);
              }
            }}
          >
            <label className="field">
              <span>Trenutna lozinka</span>
              <input name="current" type="password" required autoComplete="current-password" />
            </label>
            <label className="field">
              <span>Nova lozinka</span>
              <input name="next" type="password" required minLength={10} autoComplete="new-password" />
            </label>
            <button className="btn">Promeni lozinku</button>
          </form>
        </div>
        <div className="box">
          <h2>Dvofaktorska prijava</h2>
          {admin.totpEnabled ? (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                try {
                  await post('/api/admin/auth/totp/disable', { password: new FormData(e.currentTarget).get('password') });
                  toast('Dvofaktorska prijava je isključena');
                  await refresh();
                } catch (err: any) {
                  toast(err.message, true);
                }
              }}
            >
              <div className="notice ok">Uključena. Pri prijavi se traži kod iz aplikacije za autentifikaciju.</div>
              <label className="field">
                <span>Lozinka za isključivanje</span>
                <input name="password" type="password" required />
              </label>
              <button className="btn danger">Isključi</button>
            </form>
          ) : !setup ? (
            <>
              <p className="muted">Pri prijavi će se, pored lozinke, tražiti kod iz aplikacije kao što su Google Authenticator ili Authy. Preporučeno za sve naloge.</p>
              <button
                className="btn"
                onClick={async () => {
                  const r = await post('/api/admin/auth/totp/setup');
                  setSetup({ ...r, qr: await QRCode.toDataURL(r.otpauthUrl, { width: 200, margin: 1 }) });
                }}
              >
                Uključi
              </button>
            </>
          ) : (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                setError('');
                try {
                  await post('/api/admin/auth/totp/enable', { secret: setup.secret, code });
                  toast('Dvofaktorska prijava je uključena');
                  setSetup(null);
                  await refresh();
                } catch (err: any) {
                  setError(err.message);
                }
              }}
            >
              <p className="small">Skenirajte QR kod aplikacijom za autentifikaciju, pa unesite kod koji ona prikazuje.</p>
              <img src={setup.qr} alt="QR kod za aplikaciju za autentifikaciju" width={200} height={200} />
              <p className="small muted" style={{ wordBreak: 'break-all' }}>
                Ručni unos: {setup.secret}
              </p>
              <label className="field">
                <span>Kod iz aplikacije</span>
                <input value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" maxLength={6} required />
              </label>
              {error && <div className="notice bad">{error}</div>}
              <button className="btn">Potvrdi</button>
            </form>
          )}
        </div>
      </div>
    </>
  );
}
