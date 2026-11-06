import { buildApp } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const { app, close } = await buildApp(config);

const shutdown = async (signal: string) => {
  app.log.info(`${signal} — gašenje servera`);
  await close();
  process.exit(0);
};
process.on('SIGINT', () => void shutdown('SIGINT'));
process.on('SIGTERM', () => void shutdown('SIGTERM'));

await app.listen({ port: config.port, host: config.host });
app.log.info(`${config.appName} server radi na ${config.publicUrl}`);
