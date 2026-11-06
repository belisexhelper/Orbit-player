import fs from 'node:fs';
import pg from 'pg';

export interface QueryResult<T> {
  rows: T[];
}

export interface Queryable {
  query<T = any>(text: string, params?: unknown[]): Promise<QueryResult<T>>;
}

export interface Db extends Queryable {
  tx<R>(fn: (q: Queryable) => Promise<R>): Promise<R>;
  close(): Promise<void>;
  kind: 'postgres' | 'pglite';
}

// Vrati bigint (COUNT) kao broj, ne string
pg.types.setTypeParser(20, (v: string) => Number(v));

export async function openDb(url: string): Promise<Db> {
  if (url.startsWith('pglite:')) {
    const target = url.slice('pglite:'.length);
    const { PGlite } = await import('@electric-sql/pglite');
    let lite;
    if (target === 'memory' || target === '') {
      lite = new PGlite();
    } else {
      fs.mkdirSync(target, { recursive: true });
      lite = new PGlite(target);
    }
    await lite.waitReady;
    // pglite izvršava jedan upit u isto vreme; transakcije serijalizujemo kroz red
    let chain: Promise<unknown> = Promise.resolve();
    const serial = <R>(fn: () => Promise<R>): Promise<R> => {
      const next = chain.then(fn, fn);
      chain = next.catch(() => undefined);
      return next;
    };
    const norm = (r: any) => ({ rows: (r.rows ?? []).map(fixBigints) });
    return {
      kind: 'pglite',
      query: (text, params) => serial(async () => norm(await lite.query(text, params as any[]))) as any,
      tx: (fn) =>
        serial(() =>
          lite.transaction(async (t) => fn({ query: async (text, params) => norm(await t.query(text, params as any[])) as any })),
        ),
      close: () => lite.close(),
    };
  }

  const pool = new pg.Pool({ connectionString: url, max: 10 });
  await pool.query('select 1');
  return {
    kind: 'postgres',
    query: (text, params) => pool.query(text, params as any[]) as any,
    tx: async (fn) => {
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const r = await fn({ query: (t, p) => client.query(t, p as any[]) as any });
        await client.query('COMMIT');
        return r;
      } catch (e) {
        await client.query('ROLLBACK').catch(() => undefined);
        throw e;
      } finally {
        client.release();
      }
    },
    close: () => pool.end(),
  };
}

function fixBigints(row: Record<string, unknown>) {
  for (const k of Object.keys(row)) {
    if (typeof row[k] === 'bigint') row[k] = Number(row[k]);
  }
  return row;
}
