import type { FastifyBaseLogger } from 'fastify';
import type { AppConfig } from '../config.js';
import type { Db } from '../db/index.js';
import type { DeviceHub } from './hub.js';

export interface Ctx {
  config: AppConfig;
  db: Db;
  hub: DeviceHub;
  log: FastifyBaseLogger;
}
