import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom';
import { ApiError, get, post } from '../api';

export interface AdminUser {
  id: string;
  username: string;
  role: 'super' | 'support' | 'reseller';
  totpEnabled: boolean;
}

interface AuthState {
  admin: AdminUser;
  appName: string;
  publicUrl: string;
  refresh: () => Promise<void>;
}

const AuthCtx = createContext<AuthState | null>(null);
export const useAuth = () => useContext(AuthCtx)!;
export const can = (a: AdminUser, ...roles: AdminUser['role'][]) => roles.includes(a.role);

export const ROLE_LABEL: Record<string, string> = { super: 'Glavni admin', support: 'Podrška', reseller: 'Reseler' };

export function AdminRoot() {
  const [state, setState] = useState<{ admin: AdminUser; appName: string; publicUrl: string } | null | undefined>(undefined);
  const load = async () => {
    try {
      const r = await get('/api/admin/auth/me');
      setState(r);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) setState(null);
      else setState(null);
    }
  };
  useEffect(() => {
    void load();
  }, []);
  useEffect(() => {
    document.title = 'Admin panel';
  }, []);

  if (state === undefined) return <div className="login" />;
  if (state === null) return <Login onDone={load} />;
  return (
    <AuthCtx.Provider value={{ ...state, refresh: load }}>
      <Layout />
    </AuthCtx.Provider>
  );
}

function Login({ onDone }: { onDone: () => Promise<void> }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [totp, setTotp] = useState('');
  const [needTotp, setNeedTotp] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <div className="login">
      <form
        className="modal"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError('');
          try {
            await post('/api/admin/auth/login', { username, password, totp: needTotp ? totp : undefined });
            await onDone();
          } catch (err: any) {
            if (err.code === 'totp_required') {
              setNeedTotp(true);
            } else setError(err.message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <h2>Prijava u admin panel</h2>
        {!needTotp ? (
          <>
            <label className="field">
              <span>Korisničko ime</span>
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
            </label>
            <label className="field">
              <span>Lozinka</span>
              <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" autoComplete="current-password" required />
            </label>
          </>
        ) : (
          <label className="field">
            <span>Kod iz aplikacije za autentifikaciju</span>
            <input value={totp} onChange={(e) => setTotp(e.target.value)} inputMode="numeric" autoComplete="one-time-code" autoFocus maxLength={6} required />
          </label>
        )}
        {error && <div className="notice bad">{error}</div>}
        <button className="btn" style={{ width: '100%' }} disabled={busy}>
          {busy ? 'Prijavljivanje…' : 'Prijavi se'}
        </button>
      </form>
    </div>
  );
}

function Layout() {
  const { admin, appName } = useAuth();
  const nav = useNavigate();
  return (
    <div className="admin">
      <aside className="side">
        <Link to="/admin" className="brand">
          <img src="/favicon.svg" alt="" style={{ width: 28, height: 28 }} />
          {appName}
        </Link>
        <nav aria-label="Admin meni">
          <NavLink to="/admin" end>
            Pregled
          </NavLink>
          <NavLink to="/admin/devices">Uređaji</NavLink>
          <NavLink to="/admin/profiles">Korisnici</NavLink>
          <NavLink to="/admin/launchers">Launcher i kiosk</NavLink>
          {can(admin, 'super', 'reseller') && <NavLink to="/admin/messages">Poruke na ekranu</NavLink>}
          {can(admin, 'super') && (
            <>
              <div className="group">Sistem</div>
              <NavLink to="/admin/settings">Podešavanja aplikacije</NavLink>
              <NavLink to="/admin/releases">Verzije aplikacije</NavLink>
              <NavLink to="/admin/admins">Admin nalozi</NavLink>
              <NavLink to="/admin/audit">Istorija izmena</NavLink>
            </>
          )}
        </nav>
        <div className="who">
          <div style={{ color: '#fff', fontWeight: 600 }}>{admin.username}</div>
          <div style={{ marginBottom: 8 }}>{ROLE_LABEL[admin.role]}</div>
          <div className="row" style={{ gap: 12 }}>
            <Link to="/admin/account" style={{ color: '#9fb0d4' }}>
              Moj nalog
            </Link>
            <button
              onClick={async () => {
                await post('/api/admin/auth/logout');
                nav('/admin');
                window.location.reload();
              }}
            >
              Odjava
            </button>
          </div>
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}

export function PageHead({ title, sub, children }: { title: string; sub?: ReactNode; children?: ReactNode }) {
  useEffect(() => {
    document.title = `${title} — Admin`;
  }, [title]);
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {children && <div className="row">{children}</div>}
    </div>
  );
}

/** Jednostavno učitavanje podataka sa osvežavanjem */
export function useLoad<T>(url: string | null, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState('');
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!url) return;
    let cancel = false;
    get<T>(url)
      .then((d) => !cancel && (setData(d), setError('')))
      .catch((e) => !cancel && setError(e.message));
    return () => {
      cancel = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url, tick, ...deps]);
  return { data, error, reload: () => setTick((t) => t + 1), setData };
}
