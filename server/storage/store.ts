import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { SQLInputValue } from 'node:sqlite';
import { DatabaseSync } from 'node:sqlite';
import type { ChatSource, ReplyState } from '../../shared/types.ts';
import { hasCode, httpError, now, parse } from '../common/runtime.ts';
import type { StoredTables, Transport } from '../common/types.ts';
import { config } from '../config/env.ts';
import { initializeSchema } from './schema.ts';
import { storageConfig } from './storage.config.ts';

mkdirSync(config.dataDir, { recursive: true });
const processLock = new DatabaseSync(join(config.dataDir, storageConfig.lockDatabase), { timeout: 0 });
try {
  processLock.exec('BEGIN EXCLUSIVE');
} catch (error) {
  processLock.close();
  throw new Error('该数据目录已有工作台进程，请关闭它或使用独立数据目录', { cause: error });
}
export const db = new DatabaseSync(join(config.dataDir, storageConfig.database));
initializeSchema(db, transaction);
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

const keyPath = join(config.dataDir, storageConfig.credentialKey);
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
