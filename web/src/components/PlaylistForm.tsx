import { useState } from 'react';

export type PlaylistInput =
  | { type: 'xtream'; name: string; server: string; username: string; password: string; epgUrl: string }
  | { type: 'm3u'; name: string; url: string; epgUrl: string };

export interface TestResult {
  ok: boolean;
  message: string;
  info: Record<string, any>;
}

interface Props {
  initial?: { type: 'xtream' | 'm3u'; name?: string; server?: string; username?: string; epgUrl?: string };
  /** Kod izmene postojeće liste prazna lozinka/adresa znači „zadrži staru” */
  editing?: boolean;
  submitLabel: string;
  onSubmit: (p: PlaylistInput) => Promise<void>;
  onTest?: (p: PlaylistInput) => Promise<TestResult>;
  onCancel?: () => void;
}

export function describeTest(t: TestResult) {
  const i = t.info ?? {};
  const parts: string[] = [];
  if (i.expiresAt) parts.push(`ističe ${new Date(i.expiresAt).toLocaleDateString('sr-Latn-RS')}`);
  if (i.maxConnections != null) parts.push(`najviše ${i.maxConnections} istovremenih veza`);
  if (i.liveCategories != null) parts.push(`${i.liveCategories} grupa kanala`);
  if (i.vodCategories != null) parts.push(`${i.vodCategories} grupa filmova`);
  if (i.seriesCategories != null) parts.push(`${i.seriesCategories} grupa serija`);
  return parts.length ? `${t.message} ${parts.join(', ')}.` : t.message;
}

export function PlaylistForm({ initial, editing, submitLabel, onSubmit, onTest, onCancel }: Props) {
  const [type, setType] = useState<'xtream' | 'm3u'>(initial?.type ?? 'xtream');
  const [name, setName] = useState(initial?.name ?? '');
  const [server, setServer] = useState(initial?.server ?? '');
  const [username, setUsername] = useState(initial?.username ?? '');
  const [password, setPassword] = useState('');
  const [url, setUrl] = useState('');
  const [epgUrl, setEpgUrl] = useState(initial?.epgUrl ?? '');
  const [busy, setBusy] = useState<'' | 'test' | 'save'>('');
  const [result, setResult] = useState<TestResult | null>(null);
  const [error, setError] = useState('');

  const build = (): PlaylistInput => {
    const n = name.trim() || (type === 'xtream' ? 'Moja lista' : 'M3U lista');
    if (type === 'xtream') return { type, name: n, server, username, password: editing && !password ? '__keep__' : password, epgUrl };
    return { type, name: n, url: editing && !url ? '__keep__' : url, epgUrl };
  };

  const run = async (kind: 'test' | 'save') => {
    setError('');
    setResult(null);
    setBusy(kind);
    try {
      if (kind === 'test' && onTest) setResult(await onTest(build()));
      else await onSubmit(build());
    } catch (e: any) {
      setError(e.message ?? 'Greška');
    } finally {
      setBusy('');
    }
  };

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void run('save');
      }}
    >
      <div className="field" style={{ marginBottom: 16 }}>
        <div className="seg" role="tablist" aria-label="Vrsta liste">
          <button type="button" className={type === 'xtream' ? 'on' : ''} onClick={() => setType('xtream')} disabled={editing && initial?.type !== 'xtream'}>
            Xtream Codes
          </button>
          <button type="button" className={type === 'm3u' ? 'on' : ''} onClick={() => setType('m3u')} disabled={editing && initial?.type !== 'm3u'}>
            M3U link
          </button>
        </div>
      </div>

      <label className="field">
        <span>Naziv liste</span>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="npr. Dnevna soba" maxLength={80} />
      </label>

      {type === 'xtream' ? (
        <>
          <label className="field">
            <span>Adresa servera</span>
            <input value={server} onChange={(e) => setServer(e.target.value)} placeholder="http://server.com:8080" inputMode="url" autoCapitalize="off" autoCorrect="off" spellCheck={false} required />
            <small>Dobijate je od svog IPTV provajdera, zajedno sa korisničkim imenom i lozinkom.</small>
          </label>
          <div className="grid2">
            <label className="field">
              <span>Korisničko ime</span>
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoCapitalize="off" autoCorrect="off" spellCheck={false} required />
            </label>
            <label className="field">
              <span>Lozinka</span>
              <input
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                type="password"
                autoComplete="new-password"
                placeholder={editing ? 'Ostavite prazno da se ne menja' : ''}
                required={!editing}
              />
            </label>
          </div>
        </>
      ) : (
        <label className="field">
          <span>M3U adresa</span>
          <input
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder={editing ? 'Ostavite prazno da se ne menja' : 'http://server.com/get.php?username=…&type=m3u_plus'}
            inputMode="url"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            required={!editing}
          />
        </label>
      )}

      <label className="field">
        <span>EPG adresa (TV vodič) — nije obavezno</span>
        <input value={epgUrl} onChange={(e) => setEpgUrl(e.target.value)} placeholder="http://…/xmltv.php" inputMode="url" autoCapitalize="off" spellCheck={false} />
        <small>Za Xtream liste vodič se preuzima automatski, pa ovo polje možete ostaviti prazno.</small>
      </label>

      {result && <div className={`notice ${result.ok ? 'ok' : 'bad'}`}>{describeTest(result)}</div>}
      {error && <div className="notice bad">{error}</div>}

      <div className="row" style={{ justifyContent: 'flex-end' }}>
        {onCancel && (
          <button type="button" className="btn secondary" onClick={onCancel}>
            Odustani
          </button>
        )}
        {onTest && (
          <button type="button" className="btn secondary" disabled={!!busy} onClick={() => void run('test')}>
            {busy === 'test' ? 'Proveravam…' : 'Proveri vezu'}
          </button>
        )}
        <button type="submit" className="btn" disabled={!!busy}>
          {busy === 'save' ? 'Čuvam…' : submitLabel}
        </button>
      </div>
    </form>
  );
}
