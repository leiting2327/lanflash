import crypto from 'node:crypto';

/**
 * Signed license token:
 *   LF1.<base64url(payload json)>.<base64url(hmac-sha256)>
 *
 * The mobile clients embed the same secret and verify the signature locally,
 * so a token keeps working offline until it expires. Rotate the secret when it
 * leaks and rebuild the apps.
 */
export function signToken(payload, secret) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
  const sig = crypto.createHmac('sha256', secret).update(`LF1.${body}`).digest('base64url');
  return `LF1.${body}.${sig}`;
}

export function verifyToken(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3 || parts[0] !== 'LF1') return null;
  const expected = crypto.createHmac('sha256', secret).update(`LF1.${parts[1]}`).digest();
  let given;
  try {
    given = Buffer.from(parts[2], 'base64url');
  } catch {
    return null;
  }
  if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) return null;
  let payload;
  try {
    payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (!payload || typeof payload.exp !== 'number') return null;
  if (payload.exp * 1000 <= Date.now()) return null;
  return payload;
}

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export function newCode() {
  const groups = [];
  for (let g = 0; g < 3; g += 1) {
    let s = '';
    for (let i = 0; i < 4; i += 1) {
      s += ALPHABET[crypto.randomInt(ALPHABET.length)];
    }
    groups.push(s);
  }
  return `LF-${groups.join('-')}`;
}

export function normalizeCode(code) {
  return String(code || '').trim().toUpperCase().replace(/\s+/g, '');
}

export function randomSecret(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url');
}
