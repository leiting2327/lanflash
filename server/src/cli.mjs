import fs from 'node:fs';
import path from 'node:path';
import { openDb } from './db.mjs';
import { newCode, normalizeCode, randomSecret } from './license.mjs';

const [cmd = 'help', ...rest] = process.argv.slice(2);
const DATA_DIR = process.env.DATA_DIR || './data';

function arg(name, fallback) {
  const i = rest.indexOf(`--${name}`);
  if (i >= 0 && rest[i + 1]) return rest[i + 1];
  const eq = rest.find((v) => v.startsWith(`--${name}=`));
  if (eq) return eq.split('=').slice(1).join('=');
  return fallback;
}

function keygen() {
  const file = path.join(DATA_DIR, 'secret.env');
  fs.mkdirSync(DATA_DIR, { recursive: true });
  if (fs.existsSync(file)) {
    console.log(`secret file already exists: ${file}`);
    console.log(fs.readFileSync(file, 'utf8'));
    return;
  }
  const secret = randomSecret(32);
  const admin = randomSecret(24);
  const content = `LICENSE_SECRET=${secret}\nADMIN_KEY=${admin}\n`;
  fs.writeFileSync(file, content, { mode: 0o600 });
  console.log(`wrote ${file}`);
  console.log(content);
  console.log('# put LICENSE_SECRET into the mobile build secrets; keep ADMIN_KEY server-side');
}

function gen() {
  const db = openDb(DATA_DIR);
  const count = Math.min(1000, Math.max(1, Number(arg('count', '10'))));
  const devices = Math.max(1, Number(arg('devices', '2')));
  const days = Math.max(0, Number(arg('days', '365')));
  const plan = arg('plan', 'pro');
  const note = arg('note', '');
  const expiresAt = days > 0 ? Math.floor(Date.now() / 1000) + days * 86_400 : 0;
  const insert = db.prepare(
    'INSERT OR IGNORE INTO codes (code, plan, max_devices, expires_at, disabled, created_at, note) VALUES (?, ?, ?, ?, 0, ?, ?)'
  );
  const out = [];
  let guard = 0;
  while (out.length < count && guard < count * 50) {
    guard += 1;
    const code = newCode();
    const info = insert.run(code, plan, devices, expiresAt, Math.floor(Date.now() / 1000), note);
    if (info.changes > 0) out.push(code);
  }
  console.log(out.join('\n'));
  console.log(`\n# generated ${out.length} codes, ${devices} device(s) each, expires in ${days} day(s)`);
}

function list() {
  const db = openDb(DATA_DIR);
  const rows = db
    .prepare(
      `SELECT c.code, c.plan, c.max_devices, c.expires_at, c.disabled,
              (SELECT COUNT(*) FROM activations a WHERE a.code = c.code) AS used
       FROM codes c ORDER BY c.created_at DESC LIMIT 200`
    )
    .all();
  if (!rows.length) {
    console.log('no codes yet');
    return;
  }
  for (const r of rows) {
    const exp = r.expires_at ? new Date(r.expires_at * 1000).toISOString().slice(0, 10) : 'never';
    console.log(
      `${r.code}  plan=${r.plan}  used=${r.used}/${r.max_devices}  expires=${exp}${r.disabled ? '  [disabled]' : ''}`
    );
  }
}

function revoke() {
  const db = openDb(DATA_DIR);
  const code = normalizeCode(arg('code', ''));
  if (!code) {
    console.log('usage: npm run revoke -- --code LF-XXXX-XXXX-XXXX');
    return;
  }
  const on = arg('enable', '') === 'true' ? 0 : 1;
  const info = db.prepare('UPDATE codes SET disabled = ? WHERE code = ?').run(on, code);
  console.log(info.changes ? `updated ${code} -> disabled=${on}` : `code not found: ${code}`);
}

switch (cmd) {
  case 'keygen':
    keygen();
    break;
  case 'gen':
    gen();
    break;
  case 'list':
    list();
    break;
  case 'revoke':
    revoke();
    break;
  default:
    console.log(`LanFlash license CLI

  node src/cli.mjs keygen                     generate LICENSE_SECRET / ADMIN_KEY
  node src/cli.mjs gen --count 20 --days 365 --devices 2
  node src/cli.mjs list
  node src/cli.mjs revoke --code LF-XXXX-XXXX-XXXX
`);
}
