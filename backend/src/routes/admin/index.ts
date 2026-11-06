import type { FastifyInstance } from 'fastify';
import { forbidden } from '../../lib/errors.js';
import type { Ctx } from '../../services/context.js';
import { authRoutes, loadAdmin } from './auth.js';
import { deviceRoutes } from './devices.js';
import { miscRoutes } from './misc.js';
import { profileRoutes } from './profiles.js';

export function adminRoutes(ctx: Ctx) {
  return async (app: FastifyInstance) => {
    await app.register(authRoutes(ctx), { prefix: '/auth' });

    await app.register(async (s) => {
      s.addHook('preHandler', async (req) => {
        req.admin = await loadAdmin(ctx, req);
        // Podrška (support) može samo da čita i šalje komande; izmene dozvoljava svaka ruta posebno.
        if (req.admin.role === 'support' && req.method === 'DELETE') throw forbidden();
      });
      await s.register(profileRoutes(ctx));
      await s.register(deviceRoutes(ctx));
      await s.register(miscRoutes(ctx));
    });
  };
}
