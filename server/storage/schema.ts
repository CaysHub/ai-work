import type { DatabaseSync } from 'node:sqlite';
import { storageConfig } from './storage.config.ts';

export function initializeSchema(db: DatabaseSync, transaction: <T>(work: () => T) => T) {
  db.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    PRAGMA busy_timeout = ${storageConfig.busyTimeoutMs};
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
  const reviewColumns = new Set(db.prepare('PRAGMA table_info(reply_reviews)').all().map((column) => column.name));
  if (!reviewColumns.has('notice_receipt')) db.exec('ALTER TABLE reply_reviews ADD COLUMN notice_receipt TEXT');
  if (!reviewColumns.has('notice_channel')) db.exec('ALTER TABLE reply_reviews ADD COLUMN notice_channel TEXT');
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
}
