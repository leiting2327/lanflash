import http from 'node:http';
import { openDb } from './db.mjs';
import { newCode, normalizeCode, signToken, verifyToken } from './license.mjs';

const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || './data';
const SECRET = process.env.LICENSE_SECRET || '';
const ADMIN_KEY = process.env.ADMIN_KEY || '';
const TOKEN_DAYS = Math.max(1, Number(process.env.TOKEN_DAYS || 365));

if (SECRET.length < 16) {
  console.error('[fatal] LICENSE_SECRET is required (>= 16 chars). Run `npm run keygen`.');
  process.exit(1);
}
if (ADMIN_KEY.length < 16) {
  console.error('[warn] ADMIN_KEY is weak or missing; admin endpoints will be disabled until it is set.');
}

const db = openDb(DATA_DIR);
const nowSec = () => Math.floor(Date.now() / 1000);

const hits = new Map();
function rateLimited(ip) {
  const now = Date.now();
  const slot = hits.get(ip);
  if (!slot || slot.reset < now) {
    hits.set(ip, { count: 1, reset: now + 60_000 });
    return false;
  }
  slot.count += 1;
  return slot.count > 60;
}
setInterval(() => {
  const now = Date.now();
  for (const [ip, slot] of hits) if (slot.reset < now) hits.delete(ip);
}, 60_000).unref();

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Key',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 64 * 1024) throw new Error('payload too large');
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new Error('invalid json');
  }
}

function isAdmin(req) {
  return ADMIN_KEY.length >= 16 && req.headers['x-admin-key'] === ADMIN_KEY;
}

async function activate(body, res) {
  const code = normalizeCode(body.code);
  const deviceId = String(body.deviceId || '').trim().slice(0, 128);
  if (!code) return send(res, 400, { error: '请输入激活码' });
  if (!deviceId) return send(res, 400, { error: '缺少设备标识' });

  const row = db.prepare('SELECT * FROM codes WHERE code = ?').get(code);
  if (!row) return send(res, 404, { error: '激活码不存在' });
  if (row.disabled) return send(res, 403, { error: '激活码已停用' });
  if (row.expires_at && row.expires_at < nowSec()) {
    return send(res, 403, { error: '激活码已过期' });
  }

  const existing = db
    .prepare('SELECT * FROM activations WHERE code = ? AND device_id = ?')
    .get(code, deviceId);
  if (!existing) {
    const used = db.prepare('SELECT COUNT(*) AS n FROM activations WHERE code = ?').get(code).n;
    if (used >= row.max_devices) {
      return send(res, 409, { error: '该激活码已达到设备数量上限' });
    }
    db.prepare(
      'INSERT INTO activations (code, device_id, platform, created_at, last_seen) VALUES (?, ?, ?, ?, ?)'
    ).run(code, deviceId, String(body.platform || '').slice(0, 16), nowSec(), nowSec());
  } else {
    db.prepare('UPDATE activations SET last_seen = ?, platform = ? WHERE code = ? AND device_id = ?')
      .run(nowSec(), String(body.platform || existing.platform || '').slice(0, 16), code, deviceId);
  }

  const exp = nowSec() + TOKEN_DAYS * 86_400;
  const token = signToken(
    { did: deviceId, plan: row.plan, code, exp, iat: nowSec() },
    SECRET
  );
  return send(res, 200, { token, exp, plan: row.plan, tokenDays: TOKEN_DAYS });
}

async function verify(body, res) {
  const deviceId = String(body.deviceId || '').trim();
  const payload = verifyToken(body.token, SECRET);
  if (!payload || payload.did !== deviceId) {
    return send(res, 401, { ok: false, error: '授权已失效，请重新激活' });
  }
  const row = db.prepare('SELECT * FROM codes WHERE code = ?').get(payload.code);
  if (!row || row.disabled) {
    return send(res, 403, { ok: false, error: '激活码已停用' });
  }
  return send(res, 200, { ok: true, plan: payload.plan, exp: payload.exp });
}

async function adminGenerate(req, body, res) {
  if (!isAdmin(req)) return send(res, 401, { error: 'unauthorized' });
  const count = Math.min(1000, Math.max(1, Number(body.count || 1)));
  const maxDevices = Math.min(100, Math.max(1, Number(body.maxDevices || 2)));
  const days = Math.max(0, Number(body.days ?? 365));
  const plan = String(body.plan || 'pro').slice(0, 24);
  const note = String(body.note || '').slice(0, 200);
  const expiresAt = days > 0 ? nowSec() + days * 86_400 : 0;

  const insert = db.prepare(
    'INSERT OR IGNORE INTO codes (code, plan, max_devices, expires_at, disabled, created_at, note) VALUES (?, ?, ?, ?, 0, ?, ?)'
  );
  const codes = [];
  let guard = 0;
  while (codes.length < count && guard < count * 50) {
    guard += 1;
    const code = newCode();
    const info = insert.run(code, plan, maxDevices, expiresAt, nowSec(), note);
    if (info.changes > 0) codes.push(code);
  }
  return send(res, 200, { codes, plan, maxDevices, expiresAt });
}

const server = http.createServer(async (req, res) => {
  const ip = req.socket.remoteAddress || 'unknown';
  if (req.method === 'OPTIONS') {
    send(res, 204, {});
    return;
  }
  if (rateLimited(ip)) {
    send(res, 429, { error: '请求过于频繁，请稍后再试' });
    return;
  }

  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      send(res, 200, { ok: true, time: nowSec() });
      return;
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/activate') {
      await activate(await readJson(req), res);
      return;
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/verify') {
      await verify(await readJson(req), res);
      return;
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/admin/codes') {
      await adminGenerate(req, await readJson(req), res);
      return;
    }
    if (req.method === 'GET' && url.pathname === '/api/v1/admin/codes') {
      if (!isAdmin(req)) return send(res, 401, { error: 'unauthorized' });
      const limit = Math.min(500, Math.max(1, Number(url.searchParams.get('limit') || 100)));
      const rows = db
        .prepare(
          `SELECT c.code, c.plan, c.max_devices, c.expires_at, c.disabled, c.created_at, c.note,
                  (SELECT COUNT(*) FROM activations a WHERE a.code = c.code) AS used
           FROM codes c ORDER BY c.created_at DESC LIMIT ?`
        )
        .all(limit);
      return send(res, 200, { codes: rows });
    }
    if (req.method === 'POST' && url.pathname === '/api/v1/admin/disable') {
      if (!isAdmin(req)) return send(res, 401, { error: 'unauthorized' });
      const body = await readJson(req);
      const code = normalizeCode(body.code);
      const disabled = body.disabled === false ? 0 : 1;
      const info = db.prepare('UPDATE codes SET disabled = ? WHERE code = ?').run(disabled, code);
      return send(res, 200, { ok: info.changes > 0 });
    }
    send(res, 404, { error: 'not found' });
  } catch (err) {
    console.error('[error]', err.message);
    send(res, 400, { error: err.message || 'bad request' });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[lanflash] license service listening on :${PORT}`);
  console.log(`[lanflash] data dir: ${DATA_DIR}`);
});
