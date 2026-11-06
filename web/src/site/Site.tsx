import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useNavigate, useSearchParams } from 'react-router-dom';
import QRCode from 'qrcode';
import { del, formatBytes, formatDate, get, post, put } from '../api';
import { CodeInput, useToast } from '../components/ui';
import { PlaylistForm, describeTest, type PlaylistInput, type TestResult } from '../components/PlaylistForm';

export const APP_NAME = 'Orbit Player';

function usePublicInfo() {
  const [info, setInfo] = useState<any>(null);
  useEffect(() => {
    get('/api/public/info').then(setInfo).catch(() => setInfo({ downloads: {}, site: {} }));
  }, []);
  return info;
}

export function SiteLayout() {
  const info = usePublicInfo();
  return (
    <>
      <header className="site-header">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" />
          {info?.appName ?? APP_NAME}
        </Link>
        <nav className="site-nav" aria-label="Glavni meni">
          <NavLink to="/link">Poveži TV</NavLink>
          <NavLink to="/pomoc">Pomoć</NavLink>
        </nav>
      </header>
      <main className="site-main">
        {info?.site?.announcement && <div className="notice info">{info.site.announcement}</div>}
        <Outlet context={info} />
      </main>
      <footer className="site-footer">
        <div>
          <span>© {new Date().getFullYear()} {info?.site?.companyName || info?.appName || APP_NAME}</span>
          <Link to="/uslovi">Uslovi korišćenja</Link>
          <Link to="/privatnost">Privatnost</Link>
          <Link to="/pomoc">Pomoć</Link>
          {info?.site?.supportContact && <span>Podrška: {info.site.supportContact}</span>}
        </div>
      </footer>
    </>
  );
}

function CodePanel() {
  const [code, setCode] = useState('');
  const nav = useNavigate();
  const ready = /^\d{8}$/.test(code);
  return (
    <form
      className="front-panel"
      onSubmit={(e) => {
        e.preventDefault();
        if (ready) nav(`/link?code=${code}`);
      }}
    >
      <div className="panel-top">
        <span>
          <span className={`led${ready ? ' on' : ''}`} />
          Kod sa ekrana televizora
        </span>
        <span>8 cifara</span>
      </div>
      <CodeInput value={code} onChange={setCode} />
      <button className="btn big" type="submit" disabled={!ready}>
        Nastavi
      </button>
    </form>
  );
}

export function Home() {
  const info = usePublicInfo();
  const pro = info?.downloads?.pro;
  const lite = info?.downloads?.lite;
  const qrRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    if (qrRef.current) QRCode.toCanvas(qrRef.current, `${window.location.origin}/api/public/download/pro`, { width: 160, margin: 0 }).catch(() => undefined);
  }, [pro]);

  return (
    <>
      <section className="hero">
        <div>
          <h1>Lista na televizoru bez kucanja daljinskim.</h1>
          <p className="lead">Otvorite aplikaciju na televizoru, prepišite kod koji vidite na ekranu i unesite podatke svoje liste ovde, na telefonu.</p>
        </div>
        <CodePanel />
      </section>

      <section className="steps" aria-label="Kako funkcioniše">
        <ol>
          <li>
            <span className="n">1</span>
            <h3>Instalirajte aplikaciju</h3>
            <p>Na Android TV-u, TV boksu, Fire TV-u, telefonu ili tabletu.</p>
          </li>
          <li>
            <span className="n">2</span>
            <h3>Prepišite kod sa ekrana</h3>
            <p>Aplikacija pri prvom pokretanju prikazuje kod od 8 cifara i QR kod.</p>
          </li>
          <li>
            <span className="n">3</span>
            <h3>Unesite svoju listu</h3>
            <p>Xtream podatke ili M3U link koje ste dobili od provajdera. Televizor ih preuzima odmah.</p>
          </li>
        </ol>
      </section>

      <section className="downloads" id="preuzimanje">
        <div>
          <h2>Preuzimanje</h2>
          <p className="muted">Aplikacija se posle instalacije sama ažurira. Na televizoru dozvolite instalaciju iz nepoznatih izvora — uputstvo je na stranici Pomoć.</p>
          <div className="dl-list">
            <div className="dl-item">
              <div>
                <strong>Android TV, telefon i tablet</strong>
                <span className="muted small">
                  {pro ? `Verzija ${pro.versionName} · ${formatBytes(pro.size)} · ${formatDate(pro.date, false)}` : 'Uskoro'}
                </span>
              </div>
              {pro ? (
                <a className="btn" href={pro.url}>
                  Preuzmi APK
                </a>
              ) : (
                <button className="btn" disabled>
                  Preuzmi APK
                </button>
              )}
            </div>
            {lite && (
              <div className="dl-item">
                <div>
                  <strong>Lite verzija</strong>
                  <span className="muted small">Samo plejer, bez povezivanja preko sajta · {lite.versionName}</span>
                </div>
                <a className="btn secondary" href={lite.url}>
                  Preuzmi
                </a>
              </div>
            )}
          </div>
          <p className="small muted" style={{ marginTop: 14 }}>
            Za TV uređaje najlakše je preko aplikacije Downloader: upišite adresu <strong>{window.location.host}/api/public/download/pro</strong>.
          </p>
        </div>
        {pro && (
          <div className="qr">
            <canvas ref={qrRef} width={160} height={160} />
            <div>Skenirajte telefonom za preuzimanje</div>
          </div>
        )}
      </section>
    </>
  );
}

// ---------- Povezivanje (unos koda i lista) ----------

const SESSION_KEY = 'orbit-link';

export function LinkPage() {
  const [params, setParams] = useSearchParams();
  const toast = useToast();
  const [code, setCode] = useState(params.get('code') ?? '');
  const [session, setSession] = useState<{ token: string; device: string; until: number } | null>(() => {
    try {
      const s = JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? 'null');
      return s && s.until > Date.now() ? s : null;
    } catch {
      return null;
    }
  });
  const [playlists, setPlaylists] = useState<any[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [justSaved, setJustSaved] = useState<TestResult | null>(null);
  const autoTried = useRef(false);

  const start = async (c: string) => {
    const clean = c.replace(/\D/g, '');
    if (clean.length !== 8) return;
    setBusy(true);
    setError('');
    try {
      const r = await post('/api/public/link/start', { code: clean });
      const s = { token: r.sessionToken, device: r.device.name, until: Date.now() + r.expiresInSec * 1000 - 30_000 };
      sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
      setSession(s);
      setPlaylists(r.playlists);
      setAdding(r.playlists.length === 0);
      setParams({}, { replace: true });
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    const c = params.get('code');
    if (c && !autoTried.current) {
      autoTried.current = true;
      void start(c);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (session && !params.get('code')) {
      get('/api/public/link/playlists', session.token)
        .then((r) => setPlaylists(r.playlists))
        .catch(() => {
          sessionStorage.removeItem(SESSION_KEY);
          setSession(null);
        });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session?.token]);

  const finish = () => {
    sessionStorage.removeItem(SESSION_KEY);
    setSession(null);
    setPlaylists([]);
    setCode('');
  };

  if (!session) {
    return (
      <div className="link-page">
        <h1>Poveži televizor</h1>
        <p className="muted">Unesite kod od 8 cifara koji aplikacija prikazuje na ekranu televizora.</p>
        <form
          className="front-panel"
          onSubmit={(e) => {
            e.preventDefault();
            void start(code);
          }}
        >
          <CodeInput value={code} onChange={setCode} autoFocus={!params.get('code')} />
          <button className="btn big" type="submit" disabled={busy || !/^\d{8}$/.test(code)}>
            {busy ? 'Proveravam…' : 'Nastavi'}
          </button>
          <div className={`panel-msg${error ? ' bad' : ''}`} role="alert">
            {error}
          </div>
        </form>
        <p className="small muted" style={{ marginTop: 16 }}>
          Ne vidite kod? U aplikaciji otvorite Podešavanja → Liste → Dodaj preko telefona. Kod važi 15 minuta.
        </p>
      </div>
    );
  }

  const testFn = (p: PlaylistInput) => post<TestResult>('/api/public/link/test', { playlist: p }, session.token);

  return (
    <div className="link-page">
      <span className="device-chip">Povezano: {session.device || 'televizor'}</span>
      <h1>Liste na ovom uređaju</h1>

      {justSaved && (
        <div className="notice ok">
          {describeTest(justSaved)} Lista se upravo učitava na televizoru.
        </div>
      )}

      {playlists.length > 0 && (
        <div className="sheet">
          {playlists.map((p) => (
            <div className="playlist-row" key={p.id}>
              <div>
                <strong>{p.name}</strong>
                <div className="meta">{p.type === 'xtream' ? `${p.server} · ${p.username}` : p.url}</div>
              </div>
              <div className="row" style={{ flexWrap: 'nowrap' }}>
                <button className="btn ghost small" onClick={() => setEditing(p)}>
                  Izmeni
                </button>
                <button
                  className="btn ghost small"
                  style={{ color: 'var(--bad)' }}
                  onClick={async () => {
                    if (!window.confirm(`Ukloniti listu „${p.name}” sa televizora?`)) return;
                    await del(`/api/public/link/playlists/${p.id}`, session.token);
                    setPlaylists((l) => l.filter((x) => x.id !== p.id));
                    toast('Lista je uklonjena');
                  }}
                >
                  Ukloni
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {editing ? (
        <div className="sheet">
          <h2 style={{ fontSize: '1.15rem' }}>Izmena liste</h2>
          <PlaylistForm
            key={editing.id}
            editing
            initial={editing}
            submitLabel="Sačuvaj izmene"
            onTest={testFn}
            onCancel={() => setEditing(null)}
            onSubmit={async (p) => {
              const r = await put(`/api/public/link/playlists/${editing.id}`, { playlist: p }, session.token);
              setPlaylists((l) => l.map((x) => (x.id === editing.id ? r.playlist : x)));
              setEditing(null);
              toast('Izmene su sačuvane');
            }}
          />
        </div>
      ) : adding ? (
        <div className="sheet">
          <h2 style={{ fontSize: '1.15rem' }}>Nova lista</h2>
          <PlaylistForm
            submitLabel="Pošalji na televizor"
            onTest={testFn}
            onCancel={playlists.length ? () => setAdding(false) : undefined}
            onSubmit={async (p) => {
              const r = await post('/api/public/link/playlists', { playlist: p }, session.token);
              if (!r.saved) throw new Error(describeTest(r.test));
              setPlaylists((l) => [...l, r.playlist]);
              setJustSaved(r.test);
              setAdding(false);
            }}
          />
        </div>
      ) : (
        <div className="row">
          <button className="btn" onClick={() => setAdding(true)}>
            Dodaj još jednu listu
          </button>
          <button className="btn secondary" onClick={finish}>
            Završi
          </button>
        </div>
      )}
    </div>
  );
}

// ---------- Pomoć i pravne stranice ----------

export function Help() {
  return (
    <div className="prose">
      <h1>Pomoć</h1>
      <h2>Instalacija na Android TV ili TV boks</h2>
      <ol>
        <li>Na televizoru iz Play prodavnice instalirajte aplikaciju <strong>Downloader</strong>.</li>
        <li>U podešavanjima televizora dozvolite instalaciju nepoznatih aplikacija za Downloader (Podešavanja → Aplikacije → Bezbednost i ograničenja).</li>
        <li>U Downloader-u upišite adresu <strong>{window.location.host}/api/public/download/pro</strong> i instalirajte aplikaciju.</li>
        <li>Pokrenite aplikaciju i pratite uputstvo na ekranu.</li>
      </ol>
      <h2>Fire TV</h2>
      <p>Isti postupak kao gore. Opcija za nepoznate izvore je u Podešavanja → Moj Fire TV → Opcije za programere.</p>
      <h2>Telefon i tablet</h2>
      <p>Otvorite ovu stranicu na telefonu, preuzmite APK i otvorite ga. Telefon će tražiti dozvolu za instalaciju iz pregledača.</p>
      <h2>Kod ne radi</h2>
      <ul>
        <li>Kod važi 15 minuta. U aplikaciji zatražite novi kod.</li>
        <li>Proverite da je televizor povezan na internet.</li>
        <li>Posle više pogrešnih pokušaja sačekajte minut pa pokušajte ponovo.</li>
      </ul>
      <h2>Dobio sam kod za aktivaciju od prodavca</h2>
      <p>To je drugi kod: unosi se direktno na televizoru, u aplikaciji izaberite „Imam kod za aktivaciju”.</p>
      <h2>Odakle dobijam listu?</h2>
      <p>Aplikacija je samo plejer i ne sadrži kanale. Podatke za listu dobijate od svog provajdera.</p>
    </div>
  );
}

export function Terms() {
  return (
    <div className="prose">
      <h1>Uslovi korišćenja</h1>
      <p className="muted">Ovaj tekst je osnova koju treba da pregleda pravnik pre objave.</p>
      <p>Aplikacija je medijski plejer. Ne sadrži, ne prodaje i ne distribuira televizijske kanale, filmove ili druge sadržaje. Korisnik sam unosi adrese i podatke za pristup sadržaju koji je nabavio od trećih lica.</p>
      <p>Korisnik je odgovoran da ima pravo da gleda sadržaj koji reprodukuje. Zabranjeno je korišćenje aplikacije za pristup sadržaju bez dozvole nosioca prava.</p>
      <p>Prijave zloupotrebe i zahteve nosilaca prava šaljite na kontakt adresu navedenu u podnožju stranice. Odgovaramo u roku od 7 dana.</p>
    </div>
  );
}

export function Privacy() {
  return (
    <div className="prose">
      <h1>Politika privatnosti</h1>
      <p className="muted">Ovaj tekst je osnova koju treba da pregleda pravnik pre objave.</p>
      <p>Čuvamo samo podatke potrebne za rad aplikacije: identifikator uređaja, model, verziju Androida i aplikacije, IP adresu poslednjeg pristupa i podatke za liste koje unesete. Lozinke i adrese lista čuvamo šifrovano.</p>
      <p>Ne prodajemo i ne delimo podatke sa trećim licima. Podatke o uređaju i listama možete obrisati tako što uklonite liste na ovoj stranici ili se obratite podršci.</p>
    </div>
  );
}
