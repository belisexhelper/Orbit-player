/**
 * Pravljenje ili resetovanje admin naloga iz komandne linije:
 *   node dist/cli/createAdmin.js <korisnik> <lozinka> [super|support|reseller]
 */
import { openDb } from '../db/index.js';
import { migrate } from '../db/migrations.js';
import { hashPassword } from '../lib/crypto.js';
import path from 'node:path';

const [username, password, role = 'super'] = process.argv.slice(2);
if (!username || !password) {
  console.error('Upotreba: create-admin <korisnik> <lozinka> [super|support|reseller]');
  process.exit(1);
}
if (password.length < 10) {
  console.error('Lozinka mora imati bar 10 znakova');
  process.exit(1);
}
if (!['super', 'support', 'reseller'].includes(role)) {
  console.error('Uloga mora biti super, support ili reseller');
  process.exit(1);
}
const url = process.env.DATABASE_URL ?? `pglite:${path.join(process.env.DATA_DIR ?? path.resolve('data'), 'db')}`;
const db = await openDb(url);
await migrate(db);
const hash = await hashPassword(password);
await db.query(
  `INSERT INTO admins (username, password_hash, role) VALUES ($1, $2, $3)
   ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash, role = EXCLUDED.role,
     disabled = false, failed_logins = 0, locked_until = NULL`,
  [username.trim().toLowerCase(), hash, role],
);
console.log(`Admin "${username}" (${role}) je spreman.`);
await db.close();
