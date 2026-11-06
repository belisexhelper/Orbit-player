import fs from 'node:fs';
import path from 'node:path';
import Fastify, { type FastifyInstance, type FastifyRequest } from 'fastify';
import cookie from '@fastify/cookie';
import rateLimit from '@fastify/rate-limit';
import websocket from '@fastify/websocket';
import multipart from '@fastify/multipart';
import fastifyStatic from '@fastify/static';
import { ZodError } from 'zod';
import type { AppConfig } from './config.js';
import { openDb, type Db } from './db/index.js';
import { migrate } from './db/migrations.js';
import { HttpError } from './lib/errors.js';
import { DeviceHub } from './services/hub.js';
import type { Ctx } from './services/context.js';
import { deviceRoutes } from './routes/device.js';
import { publicRoutes } from './routes/public.js';
import { adminRoutes } from './routes/admin/index.js';
import { ensureBootstrapAdmin } from './services/admins.js';

export interface BuiltApp {
  app: FastifyInstance;
  ctx: Ctx;
  close: () => Promise<void>;
}

export async function buildApp(config: AppConfig, opts: { db?: Db; logger?: boolean } = {}): Promise<BuiltApp> {
  const app = Fastify({
    logger: opts.logger === false ? false : { level: config.logLevel },
    trustProxy: true,
    bodyLimit: 2 * 1024 * 1024,
  });

  const db = opts.db ?? (await openDb(config.databaseUrl));
  await migrate(db);
  const hub = new DeviceHub();
  const ctx: Ctx = { config, db, hub, log: app.log };
  fs.mkdirSync(path.join(config.dataDir, 'releases'), { recursive: true });
  await ensureBootstrapAdmin(ctx);

  await app.register(cookie, { secret: config.appSecret });
  await app.register(rateLimit, { global: false, keyGenerator: (req: FastifyRequest) => req.ip });
  await app.register(websocket, { options: { maxPayload: 256 * 1024 } });
  await app.register(multipart, { limits: { fileSize: 300 * 1024 * 1024, files: 1 } });

  app.setErrorHandler((err: any, req, reply) => {
    if (err instanceof HttpError) {
      return reply.status(err.statusCode).send({ error: err.code, message: err.message });
    }
    if (err instanceof ZodError) {
      const first = err.issues[0];
      return reply.status(400).send({
        error: 'validation',
        message: first ? `${first.path.join('.') ? first.path.join('.') + ': ' : ''}${first.message}` : 'Neispravni podaci',
        issues: err.issues,
      });
    }
    if (err.statusCode === 429) {
      return reply.status(429).send({ error: 'rate_limited', message: 'Previše pokušaja. Sačekajte malo pa pokušajte ponovo.' });
    }
    if (err.statusCode && err.statusCode < 500) {
      return reply.status(err.statusCode).send({ error: err.code ?? 'error', message: err.message });
    }
    req.log.error({ err }, 'unhandled error');
    return reply.status(500).send({ error: 'server_error', message: 'Greška na serveru' });
  });

  app.addHook('onSend', async (_req, reply, payload) => {
    reply.header('x-content-type-options', 'nosniff');
    reply.header('referrer-policy', 'same-origin');
    return payload;
  });

  app.get('/api/health', async () => ({ ok: true, time: new Date().toISOString() }));

  await app.register(deviceRoutes(ctx), { prefix: '/api/device' });
  await app.register(publicRoutes(ctx), { prefix: '/api/public' });
  await app.register(adminRoutes(ctx), { prefix: '/api/admin' });

  // Sajt i admin panel (React build) — sve nepoznate putanje vraćaju index.html
  if (config.webDir && fs.existsSync(path.join(config.webDir, 'index.html'))) {
    await app.register(fastifyStatic, { root: path.resolve(config.webDir) });
    app.setNotFoundHandler((req, reply) => {
      if (req.url.startsWith('/api/')) return reply.status(404).send({ error: 'not_found', message: 'Nije pronađeno' });
      return reply.type('text/html').sendFile('index.html');
    });
  } else {
    app.setNotFoundHandler((_req, reply) => reply.status(404).send({ error: 'not_found', message: 'Nije pronađeno' }));
  }

  return {
    app,
    ctx,
    close: async () => {
      hub.closeAll();
      await app.close();
      if (!opts.db) await db.close();
    },
  };
}
