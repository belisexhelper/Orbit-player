export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

export async function api<T = any>(
  method: string,
  url: string,
  body?: unknown,
  opts: { token?: string; form?: FormData } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  let payload: BodyInit | undefined;
  if (opts.form) payload = opts.form;
  else if (body !== undefined) {
    headers['content-type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  let res: Response;
  try {
    res = await fetch(url, { method, headers, body: payload, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, 'network', 'Nema veze sa serverom. Proverite internet i pokušajte ponovo.');
  }
  const text = await res.text();
  let json: any = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* nije JSON */
  }
  if (!res.ok) throw new ApiError(res.status, json?.error ?? 'error', json?.message ?? `Greška ${res.status}`);
  return json as T;
}

export const get = <T = any>(url: string, token?: string) => api<T>('GET', url, undefined, { token });
export const post = <T = any>(url: string, body?: unknown, token?: string) => api<T>('POST', url, body ?? {}, { token });
export const put = <T = any>(url: string, body?: unknown, token?: string) => api<T>('PUT', url, body ?? {}, { token });
export const patch = <T = any>(url: string, body?: unknown) => api<T>('PATCH', url, body ?? {});
export const del = <T = any>(url: string, token?: string) => api<T>('DELETE', url, undefined, { token });

export function formatDate(v: string | null | undefined, withTime = true) {
  if (!v) return '—';
  const d = new Date(v);
  return d.toLocaleString('sr-Latn-RS', withTime ? { dateStyle: 'medium', timeStyle: 'short' } : { dateStyle: 'medium' });
}

export function timeAgo(v: string | null | undefined) {
  if (!v) return 'nikad';
  const s = Math.round((Date.now() - new Date(v).getTime()) / 1000);
  if (s < 60) return 'upravo';
  if (s < 3600) return `pre ${Math.floor(s / 60)} min`;
  if (s < 86400) return `pre ${Math.floor(s / 3600)} h`;
  return `pre ${Math.floor(s / 86400)} d`;
}

export function formatBytes(n: number) {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB'];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / 1024 ** i).toFixed(i ? 1 : 0)} ${u[i]}`;
}
