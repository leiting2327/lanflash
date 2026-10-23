import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export function openDb(dataDir) {
  fs.mkdirSync(dataDir, { recursive: true });
  const db = new DatabaseSync(path.join(dataDir, 'lanflash.db'));
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA synchronous = NORMAL;
    CREATE TABLE IF NOT EXISTS codes (
      code        TEXT PRIMARY KEY,
      plan        TEXT NOT NULL DEFAULT 'pro',
      max_devices INTEGER NOT NULL DEFAULT 2,
      expires_at  INTEGER NOT NULL DEFAULT 0,
      disabled    INTEGER NOT NULL DEFAULT 0,
      created_at  INTEGER NOT NULL,
      note        TEXT
    );
    CREATE TABLE IF NOT EXISTS activations (
      code       TEXT NOT NULL,
      device_id  TEXT NOT NULL,
      platform   TEXT,
      created_at INTEGER NOT NULL,
      last_seen  INTEGER NOT NULL,
      PRIMARY KEY (code, device_id)
    );
    CREATE INDEX IF NOT EXISTS idx_activations_code ON activations(code);
  `);
  return db;
}
