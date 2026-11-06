import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { del, formatDate, patch, post, timeAgo } from '../api';
import { Confirm, Modal, useToast } from '../components/ui';
import { PlaylistForm, type PlaylistInput } from '../components/PlaylistForm';
import { can, PageHead, useAuth, useLoad } from './Admin';
import { DeviceName, MessageModal, OnlineDot, PlaylistTable } from './Devices';
import { SettingsEditor } from './Settings';

function expiryTag(p: any) {
  if (!p.expiresAt) return <span className="tag">bez isteka</span>;
  const days = Math.ceil((new Date(p.expiresAt).getTime() - Date.now()) / 86400000);
  if (days < 0) return <span className="tag bad">isteklo {formatDate(p.expiresAt, false)}</span>;
  if (days <= 7) return <span className="tag warn">ističe za {days} d</span>;
  return <span className="tag ok">do {formatDate(p.expiresAt, false)}</span>;
}

const toDateInput = (v: string | null) => (v ? new Date(v).toISOString().slice(0, 10) : '');
const fromDateInput = (v: string) => (v ? new Date(`${v}T23:59:59`).toISOString() : null);

export function Profiles() {
  const params = new URLSearchParams(window.location.search);
  const [filter, setFilter] = useState(params.get('filter') ?? 'all');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(params.get('new') === '1');
  const { admin } = useAuth();
  const nav = useNavigate();
  useEffect(() => {
    const t = setTimeout(() => setSearch(q), 300);
    return () => clearTimeout(t);
  }, [q]);
  const { data } = useLoad<any>(`/api/admin/profiles?filter=${filter}&q=${encodeURIComponent(search)}`);
  return (
    <>
      <PageHead title="Korisnici" sub="Korisnik ima liste, rok pretplate i ograničen broj uređaja. Uređaji se povezuju kodom za aktivaciju.">
        {can(admin, 'super', 'reseller') && (
          <button className="btn" onClick={() => setCreating(true)}>
            Novi korisnik
          </button>
        )}
      </PageHead>
      <div className="toolbar">
        <input placeholder="Pretraga: ime, kontakt, beleška" value={q} onChange={(e) => setQ(e.target.value)} />
        <select value={filter} onChange={(e) => setFilter(e.target.value)} style={{ minWidth: 0 }}>
          <option value="all">Svi</option>
          <option value="expiring">Ističu za 7 dana</option>
          <option value="expired">Istekli</option>
        </select>
      </div>
      <div className="table-wrap">
        {data && data.items.length === 0 ? (
          <div className="empty">Nema korisnika. Napravite prvog i dobićete kod koji korisnik unosi na televizoru.</div>
        ) : (
          <table className="t">
            <thead>
              <tr>
                <th>Korisnik</th>
                <th>Pretplata</th>
                <th>Uređaji</th>
                <th>Liste</th>
                <th>Napravljen</th>
              </tr>
            </thead>
            <tbody>
              {data?.items.map((p: any) => (
                <tr key={p.id} className="click" onClick={() => nav(`/admin/profiles/${p.id}`)}>
                  <td>
                    <strong>{p.name}</strong>
                    {p.contact && <div className="small muted">{p.contact}</div>}
                  </td>
                  <td>{expiryTag(p)}</td>
                  <td className="num">
                    {p.deviceCount} / {p.maxDevices}
                  </td>
                  <td className="num">{p.playlistCount}</td>
                  <td className="small muted">{formatDate(p.createdAt, false)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {creating && <NewProfileModal onClose={() => setCreating(false)} onDone={(id) => nav(`/admin/profiles/${id}?code=1`)} />}
    </>
  );
}

function ProfileFields({ v, set, launchers }: { v: any; set: (k: string, val: any) => void; launchers: any[] }) {
  return (
    <>
      <div className="grid2">
        <label className="field">
          <span>Ime ili naziv</span>
          <input value={v.name} onChange={(e) => set('name', e.target.value)} required maxLength={120} />
        </label>
        <label className="field">
          <span>Kontakt</span>
          <input value={v.contact} onChange={(e) => set('contact', e.target.value)} placeholder="telefon ili e-mail" maxLength={200} />
        </label>
        <label className="field">
          <span>Pretplata važi do</span>
          <input type="date" value={v.expires} onChange={(e) => set('expires', e.target.value)} />
          <small>Prazno = bez isteka</small>
        </label>
        <label className="field">
          <span>Najviše uređaja</span>
          <input type="number" min={1} max={100} value={v.maxDevices} onChange={(e) => set('maxDevices', Number(e.target.value))} />
        </label>
      </div>
      <label className="field">
        <span>Launcher profil</span>
        <select value={v.launcherProfileId} onChange={(e) => set('launcherProfileId', e.target.value)}>
          <option value="">Bez launchera (obična aplikacija)</option>
          {launchers.map((l: any) => (
            <option key={l.id} value={l.id}>
              {l.name}
            </option>
          ))}
        </select>
      </label>
      <label className="field">
        <span>Beleška (vidi samo admin)</span>
        <textarea value={v.note} onChange={(e) => set('note', e.target.value)} maxLength={2000} style={{ minHeight: 60 }} />
      </label>
    </>
  );
}

function NewProfileModal({ onClose, onDone }: { onClose: () => void; onDone: (id: string) => void }) {
  const { data: launchers } = useLoad<any>('/api/admin/launcher-profiles');
  const [v, setV] = useState({ name: '', contact: '', expires: '', maxDevices: 1, launcherProfileId: '', note: '' });
  const [step, setStep] = useState<1 | 2>(1);
  const [error, setError] = useState('');
  const set = (k: string, val: any) => setV((o) => ({ ...o, [k]: val }));
  const create = async (playlist?: PlaylistInput) => {
    const r = await post('/api/admin/profiles', {
      name: v.name,
      contact: v.contact,
      note: v.note,
      expiresAt: fromDateInput(v.expires),
      maxDevices: v.maxDevices,
      launcherProfileId: v.launcherProfileId || null,
      playlists: playlist ? [playlist] : [],
    });
    onDone(r.profile.id);
  };
  return (
    <Modal title={step === 1 ? 'Novi korisnik' : 'Lista za korisnika'} onClose={onClose}>
      {step === 1 ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setStep(2);
          }}
        >
          <ProfileFields v={v} set={set} launchers={launchers?.items ?? []} />
          <div className="actions">
            <button type="button" className="btn secondary" onClick={onClose}>
              Odustani
            </button>
            <button className="btn">Dalje: lista</button>
          </div>
        </form>
      ) : (
        <>
          {error && <div className="notice bad">{error}</div>}
          <PlaylistForm
            submitLabel="Napravi korisnika"
            onTest={(p) => post('/api/admin/playlists/test', { playlist: p })}
            onSubmit={create}
          />
          <p className="small muted" style={{ marginTop: 12 }}>
            Listu možete dodati i kasnije.{' '}
            <button
              className="btn ghost small"
              onClick={async () => {
                try {
                  await create();
                } catch (e: any) {
                  setError(e.message);
                }
              }}
            >
              Napravi bez liste
            </button>
          </p>
        </>
      )}
    </Modal>
  );
}

export function ProfileDetail() {
  const { id } = useParams();
  const { admin, publicUrl } = useAuth();
  const toast = useToast();
  const nav = useNavigate();
  const { data, reload } = useLoad<any>(`/api/admin/profiles/${id}`);
  const { data: launchers } = useLoad<any>('/api/admin/launcher-profiles');
  const [form, setForm] = useState<any>(null);
  const [modal, setModal] = useState<'' | 'playlist' | 'code' | 'settings' | 'delete' | 'message'>('');
  const [newCode, setNewCode] = useState<any>(null);
  const editable = can(admin, 'super', 'reseller');

  useEffect(() => {
    if (data && !form) {
      const p = data.profile;
      setForm({ name: p.name, contact: p.contact, expires: toDateInput(p.expiresAt), maxDevices: p.maxDevices, launcherProfileId: p.launcherProfileId ?? '', note: p.note });
    }
  }, [data, form]);

  useEffect(() => {
    if (data && new URLSearchParams(window.location.search).get('code') === '1' && data.activationCodes.length === 0) setModal('code');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [!!data]);

  if (!data || !form) return null;
  const p = data.profile;

  return (
    <>
      <PageHead title={p.name} sub={<>{expiryTag(p)} · {data.devices.length} / {p.maxDevices} uređaja</>}>
        {editable && (
          <button className="btn" onClick={() => setModal('code')}>
            Napravi kod za aktivaciju
          </button>
        )}
        <button className="btn secondary" onClick={() => setModal('message')} disabled={!data.devices.length}>
          Poruka na uređaje
        </button>
      </PageHead>

      <div className="cols">
        <div>
          <div className="box">
            <h2>Podaci</h2>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                await patch(`/api/admin/profiles/${id}`, {
                  name: form.name,
                  contact: form.contact,
                  note: form.note,
                  expiresAt: fromDateInput(form.expires),
                  maxDevices: form.maxDevices,
                  launcherProfileId: form.launcherProfileId || null,
                });
                toast('Korisnik je sačuvan');
                reload();
              }}
            >
              <fieldset disabled={!editable} style={{ border: 0, padding: 0, margin: 0 }}>
                <ProfileFields v={form} set={(k, val) => setForm((o: any) => ({ ...o, [k]: val }))} launchers={launchers?.items ?? []} />
                <div className="row" style={{ justifyContent: 'space-between' }}>
                  <button type="button" className="btn secondary small" onClick={() => setModal('settings')}>
                    Podešavanja aplikacije za ovog korisnika{Object.keys(p.settings ?? {}).length ? ' (postavljeno)' : ''}
                  </button>
                  <button className="btn">Sačuvaj</button>
                </div>
              </fieldset>
            </form>
          </div>

          {data.activationCodes.length > 0 && (
            <div className="box">
              <h2>Aktivni kodovi za aktivaciju</h2>
              <table className="t">
                <tbody>
                  {data.activationCodes.map((c: any) => (
                    <tr key={c.id}>
                      <td className="num">{c.hint}</td>
                      <td className="small">
                        iskorišćen {c.uses} / {c.maxUses}
                      </td>
                      <td className="small muted">važi do {formatDate(c.expiresAt)}</td>
                      <td style={{ textAlign: 'right' }}>
                        {editable && (
                          <button
                            className="btn ghost small"
                            style={{ color: 'var(--bad)' }}
                            onClick={async () => {
                              await del(`/api/admin/activation-codes/${c.id}`);
                              toast('Kod je poništen');
                              reload();
                            }}
                          >
                            Poništi
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="small muted" style={{ marginTop: 8 }}>
                Iz bezbednosnih razloga ceo kod se vidi samo kada se napravi. Ako ga je korisnik izgubio, napravite novi.
              </p>
            </div>
          )}

          {editable && (
            <button className="btn danger" onClick={() => setModal('delete')}>
              Obriši korisnika
            </button>
          )}
        </div>

        <div>
          <div className="box">
            <h2>
              Liste
              {editable && (
                <button className="btn small" onClick={() => setModal('playlist')}>
                  Dodaj listu
                </button>
              )}
            </h2>
            <PlaylistTable items={data.playlists} onChanged={reload} />
          </div>
          <div className="box">
            <h2>Uređaji</h2>
            {data.devices.length === 0 ? (
              <p className="muted">Nijedan uređaj još nije povezan. Napravite kod za aktivaciju i pošaljite ga korisniku.</p>
            ) : (
              <table className="t">
                <tbody>
                  {data.devices.map((d: any) => (
                    <tr key={d.id} className="click" onClick={() => nav(`/admin/devices/${d.id}`)}>
                      <td>
                        <div className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start', gap: 4 }}>
                          <OnlineDot online={d.online} />
                          <div>
                            <DeviceName d={d} />
                          </div>
                        </div>
                      </td>
                      <td className="small muted">{d.online ? 'sada' : timeAgo(d.lastSeenAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>

      {modal === 'code' && (
        <Modal title="Kod za aktivaciju" onClose={() => (setModal(''), setNewCode(null), reload())}>
          {!newCode ? (
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const f = new FormData(e.currentTarget);
                setNewCode(await post(`/api/admin/profiles/${id}/activation-codes`, { ttlHours: Number(f.get('ttl')), maxUses: Number(f.get('uses')) }));
              }}
            >
              <p className="muted small">Korisnik kod unosi u aplikaciji na televizoru: „Imam kod za aktivaciju”. Uređaj se povezuje sa ovim korisnikom i preuzima sve liste i podešavanja.</p>
              <div className="grid2">
                <label className="field">
                  <span>Kod važi</span>
                  <select name="ttl" defaultValue="72">
                    <option value="24">1 dan</option>
                    <option value="72">3 dana</option>
                    <option value="168">7 dana</option>
                    <option value="720">30 dana</option>
                  </select>
                </label>
                <label className="field">
                  <span>Broj uređaja koji ga mogu iskoristiti</span>
                  <input name="uses" type="number" min={1} max={100} defaultValue={Math.max(1, p.maxDevices - data.devices.length)} />
                </label>
              </div>
              <div className="actions">
                <button className="btn">Napravi kod</button>
              </div>
            </form>
          ) : (
            <>
              <div style={{ textAlign: 'center', margin: '8px 0 18px' }}>
                <span className="big-code">{newCode.formatted}</span>
                <p className="small muted" style={{ marginTop: 10 }}>
                  Važi do {formatDate(newCode.expiresAt)} · za {newCode.maxUses} {newCode.maxUses === 1 ? 'uređaj' : 'uređaja'}
                </p>
              </div>
              <label className="field">
                <span>Poruka za korisnika</span>
                <textarea
                  readOnly
                  value={`Vaš kod za aktivaciju: ${newCode.formatted}\n\n1. Preuzmite aplikaciju: ${publicUrl}\n2. Otvorite je na televizoru i izaberite „Imam kod za aktivaciju”.\n3. Unesite kod. Kanali se učitavaju automatski.`}
                  style={{ minHeight: 120 }}
                  onFocus={(e) => e.target.select()}
                />
              </label>
              <div className="actions">
                <button
                  className="btn"
                  onClick={async () => {
                    const text = `Vaš kod za aktivaciju: ${newCode.formatted}\n\n1. Preuzmite aplikaciju: ${publicUrl}\n2. Otvorite je na televizoru i izaberite „Imam kod za aktivaciju”.\n3. Unesite kod. Kanali se učitavaju automatski.`;
                    await navigator.clipboard?.writeText(text).catch(() => undefined);
                    toast('Poruka je kopirana');
                  }}
                >
                  Kopiraj poruku
                </button>
              </div>
            </>
          )}
        </Modal>
      )}
      {modal === 'playlist' && (
        <Modal title="Nova lista" onClose={() => setModal('')}>
          <PlaylistForm
            submitLabel="Dodaj listu"
            onTest={(pl) => post('/api/admin/playlists/test', { playlist: pl })}
            onCancel={() => setModal('')}
            onSubmit={async (pl) => {
              await post(`/api/admin/profiles/${id}/playlists`, { playlist: pl });
              toast('Lista je dodata i poslata uređajima');
              setModal('');
              reload();
            }}
          />
        </Modal>
      )}
      {modal === 'settings' && (
        <Modal title="Podešavanja aplikacije za ovog korisnika" onClose={() => setModal('')} wide>
          <p className="small muted">Popunjena polja važe za sve uređaje ovog korisnika i imaju prednost nad globalnim podešavanjima.</p>
          <SettingsEditor
            value={p.settings ?? {}}
            sparse
            onSave={async (v) => {
              await patch(`/api/admin/profiles/${id}`, { settings: v });
              toast('Podešavanja su sačuvana');
              setModal('');
              reload();
            }}
          />
        </Modal>
      )}
      {modal === 'message' && (
        <MessageModal
          onClose={() => setModal('')}
          onSend={async (text, durationSec) => {
            const r = await post('/api/admin/commands/bulk', { type: 'show_message', payload: { text, durationSec }, profileId: id });
            toast(`Poslato na ${r.total} uređaja (odmah: ${r.deliveredNow})`);
          }}
        />
      )}
      {modal === 'delete' && (
        <Confirm
          text={`Obrisati korisnika „${p.name}” i sve njegove liste? Povezani uređaji ostaju u sistemu, ali gube liste.`}
          confirmLabel="Obriši"
          danger
          onClose={() => setModal('')}
          onConfirm={async () => {
            await del(`/api/admin/profiles/${id}`);
            toast('Korisnik je obrisan');
            nav('/admin/profiles');
          }}
        />
      )}
    </>
  );
}
