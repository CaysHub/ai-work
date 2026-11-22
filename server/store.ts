import { DatabaseSync } from 'node:sqlite';
import type { SQLInputValue } from 'node:sqlite';
import type { ChatSource, ReplyState } from '../shared/types.ts';
import type { StoredTables, Transport } from './types.ts';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto';
import { join } from 'node:path';
import { config, hasCode, httpError, now, parse } from './config.ts';

mkdirSync(config.dataDir, { recursive: true });
const processLock = new DatabaseSync(join(config.dataDir, 'instance.sqlite'), { timeout: 0 });
try {
  processLock.exec('BEGIN EXCLUSIVE');
} catch (error) {
  processLock.close();
  throw new Error('该数据目录已有工作台进程，请关闭它或使用独立数据目录', { cause: error });
}
export const db = new DatabaseSync(join(config.dataDir, 'workbench.sqlite'));
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  PRAGMA busy_timeout = 5000;
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password TEXT NOT NULL,
    role TEXT NOT NULL, created INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS auth (
    token TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), title TEXT NOT NULL,
    source TEXT NOT NULL DEFAULT 'web', bot TEXT, target TEXT, version INTEGER,
    created INTEGER NOT NULL, updated INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0,
    UNIQUE(bot, target, version)
  );
  CREATE TABLE IF NOT EXISTS runs (
    id TEXT PRIMARY KEY, chat TEXT NOT NULL REFERENCES chats(id), prompt TEXT NOT NULL, skills TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued', answer TEXT, error TEXT, created INTEGER NOT NULL,
    updated INTEGER NOT NULL, task TEXT, tool_log TEXT NOT NULL DEFAULT '[]',
    reply TEXT NOT NULL DEFAULT 'none', transport TEXT, receipt TEXT, submitted INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS runs_chat ON runs(chat, created);
  CREATE INDEX IF NOT EXISTS runs_queue ON runs(state, created);
  CREATE INDEX IF NOT EXISTS runs_reply ON runs(reply);
  CREATE TABLE IF NOT EXISTS documents (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
    path TEXT, bytes INTEGER NOT NULL, source TEXT NOT NULL, chat TEXT REFERENCES chats(id),
    run_id TEXT REFERENCES runs(id), created INTEGER NOT NULL, blob TEXT NOT NULL, source_key TEXT,
    UNIQUE(owner,source_key)
  );
  CREATE INDEX IF NOT EXISTS documents_owner ON documents(owner,created DESC);
  CREATE INDEX IF NOT EXISTS documents_chat ON documents(chat,created DESC);
  CREATE INDEX IF NOT EXISTS documents_run ON documents(run_id);
  CREATE TABLE IF NOT EXISTS document_inputs (
    run_id TEXT NOT NULL REFERENCES runs(id), document_id TEXT NOT NULL REFERENCES documents(id),
    PRIMARY KEY(run_id,document_id)
  );
  CREATE TABLE IF NOT EXISTS document_imports (
    owner TEXT PRIMARY KEY REFERENCES users(id)
  );
  CREATE TABLE IF NOT EXISTS skills (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
    description TEXT NOT NULL, entry TEXT NOT NULL, created INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0
  );
  CREATE TABLE IF NOT EXISTS schedules (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, prompt TEXT NOT NULL,
    skills TEXT NOT NULL, cron TEXT NOT NULL, tz TEXT NOT NULL, enabled INTEGER NOT NULL,
    next INTEGER, created INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS bots (
    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, platform TEXT NOT NULL,
    mode TEXT NOT NULL, settings TEXT NOT NULL, secrets TEXT NOT NULL, enabled INTEGER NOT NULL,
    version INTEGER NOT NULL, since INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'offline',
    detail TEXT, updated INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS inbox (
    bot TEXT NOT NULL, version INTEGER NOT NULL, message TEXT NOT NULL, run TEXT NOT NULL,
    PRIMARY KEY(bot, version, message)
  );
  CREATE TABLE IF NOT EXISTS cursors (
    bot TEXT NOT NULL, version INTEGER NOT NULL, target TEXT NOT NULL, checkpoint INTEGER NOT NULL,
    PRIMARY KEY(bot, version, target)
  );
  CREATE TABLE IF NOT EXISTS tool_writes (
    id TEXT PRIMARY KEY, state TEXT NOT NULL, result TEXT
  );
  CREATE TABLE IF NOT EXISTS reply_reviews (
    id TEXT PRIMARY KEY REFERENCES runs(id), state TEXT NOT NULL,
    draft TEXT NOT NULL, created INTEGER NOT NULL, notified INTEGER, decided INTEGER
  );
  CREATE INDEX IF NOT EXISTS reply_reviews_state ON reply_reviews(state,created);
  CREATE TABLE IF NOT EXISTS feishu_review_chats (
    bot TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE, version INTEGER NOT NULL,
    target TEXT NOT NULL, since INTEGER NOT NULL, PRIMARY KEY(bot,version)
  );
  CREATE TABLE IF NOT EXISTS wecom_commands (
    id TEXT PRIMARY KEY REFERENCES runs(id), original TEXT NOT NULL REFERENCES runs(id),
    instruction TEXT NOT NULL, decision TEXT, text TEXT, feedback INTEGER NOT NULL DEFAULT 0, direct_text TEXT
  );
  CREATE TABLE IF NOT EXISTS reply_documents (
    id TEXT PRIMARY KEY REFERENCES runs(id), title TEXT NOT NULL, content TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'draft', url TEXT
  );
  CREATE TABLE IF NOT EXISTS wecom_reply_parts (
    run_id TEXT NOT NULL REFERENCES runs(id), part INTEGER NOT NULL, text TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending', receipt TEXT, attempted INTEGER, accepted INTEGER,
    PRIMARY KEY(run_id,part)
  );
  CREATE TABLE IF NOT EXISTS wecom_inbound_files (
    bot TEXT NOT NULL, version INTEGER NOT NULL, target TEXT NOT NULL, sender TEXT NOT NULL,
    message TEXT NOT NULL, media TEXT NOT NULL, name TEXT, received INTEGER NOT NULL,
    run_id TEXT REFERENCES runs(id), PRIMARY KEY(bot,version,target,message)
  );
  CREATE INDEX IF NOT EXISTS wecom_files_pending ON wecom_inbound_files(bot,version,target,sender,run_id,received);
  CREATE TABLE IF NOT EXISTS discovered_groups (
    bot TEXT NOT NULL, version INTEGER NOT NULL, target TEXT NOT NULL, name TEXT,
    first_from INTEGER NOT NULL, last_seen INTEGER NOT NULL,
    PRIMARY KEY(bot,version,target)
  );
  CREATE TABLE IF NOT EXISTS group_checkpoints (
    bot TEXT NOT NULL, version INTEGER NOT NULL, checkpoint INTEGER NOT NULL,
    PRIMARY KEY(bot,version)
  );
  CREATE TABLE IF NOT EXISTS bot_authorizations (
    state TEXT PRIMARY KEY, bot TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    version INTEGER NOT NULL, expires INTEGER NOT NULL, payload TEXT NOT NULL
  );
`);
const runColumns = new Set(db.prepare('PRAGMA table_info(runs)').all().map((column) => column.name));
if (!db.prepare('PRAGMA table_info(reply_reviews)').all().some((column) => column.name === 'notice_receipt')) {
  db.exec('ALTER TABLE reply_reviews ADD COLUMN notice_receipt TEXT');
}
if (!runColumns.has('reply_text')) db.exec('ALTER TABLE runs ADD COLUMN reply_text TEXT');
if (!runColumns.has('sent_at')) db.exec('ALTER TABLE runs ADD COLUMN sent_at INTEGER');
if (!runColumns.has('progress_log')) db.exec("ALTER TABLE runs ADD COLUMN progress_log TEXT NOT NULL DEFAULT '[]'");
const partColumns = new Set(db.prepare('PRAGMA table_info(wecom_reply_parts)').all().map((column) => column.name));
if (!partColumns.has('document_id')) db.exec('ALTER TABLE wecom_reply_parts ADD COLUMN document_id TEXT REFERENCES documents(id)');
if (!partColumns.has('media_id')) db.exec('ALTER TABLE wecom_reply_parts ADD COLUMN media_id TEXT');
if (db.prepare('PRAGMA index_list(wecom_commands)').all().some((index) => index.origin === 'u')) {
  transaction(() => db.exec(`
    CREATE TABLE wecom_command_attempts (
      id TEXT PRIMARY KEY REFERENCES runs(id), original TEXT NOT NULL REFERENCES runs(id),
      instruction TEXT NOT NULL, decision TEXT, text TEXT, feedback INTEGER NOT NULL DEFAULT 0
    );
    INSERT INTO wecom_command_attempts SELECT id,original,instruction,decision,text,feedback FROM wecom_commands;
    DROP TABLE wecom_commands;
    ALTER TABLE wecom_command_attempts RENAME TO wecom_commands;
  `));
}
if (!db.prepare('PRAGMA table_info(wecom_commands)').all().some((column) => column.name === 'direct_text')) {
  db.exec('ALTER TABLE wecom_commands ADD COLUMN direct_text TEXT');
}
export function get<T = Record<string, unknown>>(sql: string, ...args: SQLInputValue[]): T | undefined {
  return db.prepare(sql).get(...args) as T | undefined;
}
export function all<T = Record<string, unknown>>(sql: string, ...args: SQLInputValue[]): T[] {
  return db.prepare(sql).all(...args) as T[];
}
export const run = (sql: string, ...args: SQLInputValue[]) => db.prepare(sql).run(...args);
export function transaction<T>(work: () => T): T {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

const keyPath = join(config.dataDir, 'credentials.key');
try {
  writeFileSync(keyPath, randomBytes(32), { flag: 'wx', mode: 0o600 });
} catch (error) {
  if (!hasCode(error, 'EEXIST')) throw error;
}
const key = readFileSync(keyPath);
export function seal(value: unknown) {
  const nonce = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  const encrypted = Buffer.concat([cipher.update(JSON.stringify(value)), cipher.final()]);
  return Buffer.concat([nonce, cipher.getAuthTag(), encrypted]).toString('base64');
}
export function unseal<T>(value: string): T {
  const bytes = Buffer.from(value, 'base64');
  const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12));
  cipher.setAuthTag(bytes.subarray(12, 28));
  return parse<T>(Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]).toString('utf8'));
}
export function owned<T extends keyof StoredTables>(table: T, owner: string, id: string): StoredTables[T] {
  const row = get<StoredTables[T]>(`SELECT * FROM ${table} WHERE owner=? AND id=?`, owner, id);
  if (!row) throw httpError(404, '找不到此记录');
  return row;
}
export function createChat(owner: string, title: string, source: ChatSource = 'web', id: string = randomUUID()) {
  run('INSERT INTO chats (id,owner,title,source,created,updated) VALUES (?,?,?,?,?,?)', id, owner, title, source, now(), now());
  return id;
}
export function enqueue(chat: string, prompt: string, skills: string[], {
  id = randomUUID(), task = null, reply = 'none', transport = null,
}: { id?: string; task?: string | null; reply?: ReplyState; transport?: Transport | null } = {}) {
  const time = now();
  run(`INSERT INTO runs (id,chat,prompt,skills,task,reply,transport,created,updated)
       VALUES (?,?,?,?,?,?,?,?,?)`, id, chat, prompt, JSON.stringify(skills), task, reply, transport ? seal(transport) : null, time, time);
  run('UPDATE chats SET updated=? WHERE id=?', time, chat);
  return id;
}
