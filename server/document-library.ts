import { randomUUID } from 'node:crypto';
import { lstatSync, mkdirSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { basename, join, relative } from 'node:path';
import type { Express } from 'express';
import multer from 'multer';
import { z } from 'zod';
import type { Chat, DocumentRecord } from '../shared/types.ts';
import { requireUser } from './auth.ts';
import { config, hasCode, httpError, now } from './config.ts';
import { documentInlineType, previewDocument } from './document-preview.ts';
import { checkPath, workspace, writeWorkspaceFile } from './files.ts';
import { all, get, owned, run } from './store.ts';
import type { AgentRun } from './types.ts';

type StoredDocument = DocumentRecord & { blob: string };
type NewDocument = Pick<DocumentRecord, 'name' | 'path' | 'source' | 'chat' | 'run_id'> & { sourceKey?: string; created?: number };
const root = join(config.dataDir, 'documents');
const fields = 'd.id,d.name,d.path,d.bytes,d.source,d.chat,d.run_id,d.created,c.title AS chat_title';
const from = ' FROM documents d LEFT JOIN chats c ON c.id=d.chat';
const maxUploadMb = config.documentMaxBytes / (1024 * 1024);
const platformName = (name: string) => basename(name.replaceAll('\\', '/')).replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, 240);
export const documentIdsField = z.array(z.uuid()).max(10).default([]).transform((ids) => [...new Set(ids)]);

function blobPath(blob: string) {
  return checkPath(root, blob);
}

function lookup(owner: string, id: string) {
  return get<StoredDocument>('SELECT ' + fields + ',d.blob' + from + ' WHERE d.owner=? AND d.id=?', owner, id);
}

function saveDocument(owner: string, content: Buffer, document: NewDocument): DocumentRecord {
  if (content.length > config.documentMaxBytes) throw httpError(413, '文档超过 ' + maxUploadMb + ' MB');
  const previous = document.sourceKey && get<{ id: string; blob: string }>(
    'SELECT id,blob FROM documents WHERE owner=? AND source_key=?', owner, document.sourceKey);
  const id = previous ? previous.id : randomUUID();
  const blobId = randomUUID();
  const blob = blobId.slice(0, 2) + '/' + blobId;
  mkdirSync(root, { recursive: true });
  writeWorkspaceFile(root, blob, content);
  try {
    run(`INSERT INTO documents (id,owner,name,path,bytes,source,chat,run_id,created,blob,source_key)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(owner,source_key) DO UPDATE SET name=excluded.name,bytes=excluded.bytes,blob=excluded.blob`,
    id, owner, document.name, document.path, content.length, document.source, document.chat, document.run_id,
    document.created ?? now(), blob, document.sourceKey ?? null);
  } catch (error) {
    rmSync(blobPath(blob), { force: true });
    throw error;
  }
  if (previous) {
    try { rmSync(blobPath(previous.blob), { force: true }); }
    catch (error) { console.error('旧文档版本清理失败', error); }
  }
  const row = lookup(owner, id);
  if (!row) throw new Error('文档索引写入失败');
  const { blob: _blob, ...record } = row;
  return record;
}

function ownedFile(owner: string, id: string) {
  const row = lookup(owner, id);
  if (!row) throw httpError(404, '文档不存在');
  const file = blobPath(row.blob);
  const info = lstatSync(file, { throwIfNoEntry: false });
  if (!info?.isFile()) throw httpError(404, '本地文档文件不存在');
  return { row, file };
}

function workspaceKey(path: string) {
  return path.replaceAll('\\', '/').toLowerCase();
}

export function saveGeneratedDocument(row: AgentRun, path: string, content: string | Buffer) {
  const normalized = path.replaceAll('\\', '/');
  const key = 'run:' + row.id + ':' + workspaceKey(normalized);
  const data = typeof content === 'string' ? Buffer.from(content) : content;
  if (data.length > config.documentMaxBytes) throw httpError(413, '文档超过 ' + maxUploadMb + ' MB');
  checkPath(workspace(row.chat), normalized);
  writeWorkspaceFile(workspace(row.chat), normalized, data);
  return saveDocument(row.owner, data, {
    name: platformName(normalized), path: normalized, source: 'generated', chat: row.chat, run_id: row.id, sourceKey: key,
  });
}

export function saveWorkspaceUpload(chat: Chat, path: string, content: string) {
  return saveDocument(chat.owner, Buffer.from(content), {
    name: platformName(path), path: path.replaceAll('\\', '/'), source: 'upload', chat: chat.id, run_id: null,
  });
}

export function saveWecomAttachment(row: AgentRun, name: string, data: Buffer, source: string) {
  const safeName = platformName(name);
  if (!safeName) throw httpError(400, '附件缺少有效文件名');
  const key = 'wecom:' + row.chat + ':' + source;
  const existing = get<{ id: string }>('SELECT id FROM documents WHERE owner=? AND source_key=?', row.owner, key);
  return existing?.id ?? saveDocument(row.owner, data, {
    name: safeName, path: null, source: 'upload', chat: row.chat, run_id: null, sourceKey: key,
  }).id;
}

export function generatedReplyFile(runId: string) {
  return get<{ id: string; name: string }>("SELECT id,name FROM documents WHERE run_id=? AND source='generated' AND lower(name) LIKE '%.docx' ORDER BY rowid DESC LIMIT 1", runId);
}

export function readGeneratedReplyFile(owner: string, runId: string, id: string, maxBytes: number) {
  const { row, file } = ownedFile(owner, id);
  if (row.run_id !== runId || row.source !== 'generated') throw httpError(404, '找不到本轮生成的文件');
  if (row.bytes > maxBytes) throw httpError(413, '企微文件超过上传大小限制');
  return { name: row.name, bytes: readFileSync(file) };
}

// Old workspaces have file paths but no original upload or run metadata.
export function importWorkspaceDocuments(owner: string, chatId?: string) {
  const marker = get('SELECT 1 FROM document_imports WHERE owner=?', owner);
  if (marker) return;
  const chats = all<{ id: string }>('SELECT id FROM chats WHERE owner=? AND (? IS NULL OR id=?)', owner, chatId ?? null, chatId ?? null);
  for (const { id } of chats) {
    const folder = workspace(id);
    if (!lstatSync(folder, { throwIfNoEntry: false })?.isDirectory()) continue;
    const existing = new Set(all<{ path: string }>('SELECT path FROM documents WHERE owner=? AND chat=? AND path IS NOT NULL', owner, id)
      .map(({ path }) => workspaceKey(path)));
    for (const entry of readdirSync(folder, { recursive: true, withFileTypes: true })) {
      if (!entry.isFile() || entry.name.startsWith('.write-')) continue;
      const path = relative(folder, join(entry.parentPath, entry.name)).replaceAll('\\', '/');
      const key = workspaceKey(path);
      if (existing.has(key)) continue;
      const file = checkPath(folder, path);
      const stat = lstatSync(file, { throwIfNoEntry: false });
      if (!stat?.isFile() || stat.size > config.documentMaxBytes) continue;
      saveDocument(owner, readFileSync(file), {
        name: platformName(entry.name), path, source: 'workspace', chat: id, run_id: null,
        sourceKey: 'legacy:' + id + ':' + key, created: Math.round(stat.mtimeMs),
      });
    }
  }
  if (!chatId) run('INSERT OR IGNORE INTO document_imports VALUES (?)', owner);
}

export function attachDocuments(owner: string, chat: string, runId: string, ids: string[]) {
  for (const id of ids) {
    if (!lookup(owner, id)) throw httpError(404, '附件不存在');
    run('INSERT INTO document_inputs (run_id,document_id) VALUES (?,?)', runId, id);
    run('UPDATE documents SET chat=? WHERE id=? AND owner=? AND chat IS NULL', chat, id, owner);
  }
}
export function runAttachments(runId: string) {
  return all<DocumentRecord>('SELECT ' + fields + from +
    ' JOIN document_inputs i ON i.document_id=d.id WHERE i.run_id=? ORDER BY d.created,d.id', runId);
}
export function chatDocumentLinks(owner: string, chatId: string) {
  const outputs = all<DocumentRecord>('SELECT ' + fields + from +
    " WHERE d.owner=? AND d.chat=? AND d.run_id IS NOT NULL ORDER BY d.created,d.name", owner, chatId);
  const inputs = all<DocumentRecord & { input_run: string }>('SELECT ' + fields + ',i.run_id AS input_run' + from +
    ' JOIN document_inputs i ON i.document_id=d.id JOIN runs r ON r.id=i.run_id WHERE d.owner=? AND r.chat=? ORDER BY d.created,d.id', owner, chatId);
  const result = new Map<string, { attachments: DocumentRecord[]; documents: DocumentRecord[] }>();
  function links(id: string) {
    let entry = result.get(id);
    if (!entry) { entry = { attachments: [], documents: [] }; result.set(id, entry); }
    return entry;
  }
  for (const output of outputs) if (output.run_id) links(output.run_id).documents.push(output);
  for (const { input_run, ...document } of inputs) links(input_run).attachments.push(document);
  return result;
}
export function readRunDocument(row: AgentRun, id: string) {
  const allowed = get(`SELECT 1 FROM documents d WHERE d.owner=? AND d.id=? AND (d.chat=? OR EXISTS (
    SELECT 1 FROM document_inputs i JOIN runs r ON r.id=i.run_id WHERE i.document_id=d.id AND r.chat=?))`,
  row.owner, id, row.chat, row.chat);
  if (!allowed) throw httpError(404, '当前会话没有该文档');
  const document = ownedFile(row.owner, id);
  const preview = previewDocument(document.row.name, readFileSync(document.file));
  if ('text' in preview) return preview.text;
  throw httpError(400, '该文档暂不支持提取正文');
}

export function installDocumentLibrary(app: Express) {
  const upload = multer({
    storage: multer.memoryStorage(), defParamCharset: 'utf8',
    limits: { fileSize: config.documentMaxBytes, files: 1, fields: 0 },
  });
  app.get('/api/documents', (req, res) => {
    const owner = requireUser(req).id;
    const { q, source, chat, page } = z.object({
      q: z.string().trim().max(200).default(''), source: z.enum(['all', 'upload', 'generated', 'workspace']).default('all'),
      chat: z.uuid().optional(), page: z.coerce.number().int().min(1).max(100000).default(1),
    }).parse(req.query);
    if (chat) owned('chats', owner, chat);
    importWorkspaceDocuments(owner, chat);
    const condition = ` WHERE d.owner=? AND (? IS NULL OR d.chat=? OR EXISTS (
      SELECT 1 FROM document_inputs i JOIN runs r ON r.id=i.run_id WHERE i.document_id=d.id AND r.chat=?
    )) AND (? = 'all' OR d.source=?)` +
      " AND (?='' OR instr(lower(d.name),lower(?))>0 OR instr(lower(COALESCE(c.title,'')),lower(?))>0)";
    const args = [owner, chat ?? null, chat ?? null, chat ?? null, source, source, q, q, q];
    const total = get<{ count: number }>('SELECT count(*) AS count' + from + condition, ...args)?.count ?? 0;
    const rows = all<DocumentRecord>('SELECT ' + fields + from + condition +
      ' ORDER BY d.created DESC,d.id DESC LIMIT 20 OFFSET ?', ...args, (page - 1) * 20);
    res.json({ rows, total });
  });
  app.post('/api/documents', upload.single('file'), (req, res) => {
    const owner = requireUser(req).id;
    const id = z.uuid().optional().parse(req.query.chat);
    const chat = id ? owned('chats', owner, id) : null;
    if (chat && (chat.archived || chat.bot)) throw httpError(409, '当前会话不可上传文档');
    const file = req.file;
    const name = file && platformName(file.originalname);
    if (!file || !name) throw httpError(400, '请选择文档');
    res.status(201).json(saveDocument(owner, file.buffer, { name, path: null, source: 'upload', chat: chat?.id ?? null, run_id: null }));
  });
  app.get('/api/documents/:id/preview', (req, res) => {
    const { row, file } = ownedFile(requireUser(req).id, req.params.id);
    res.json(previewDocument(row.name, readFileSync(file)));
  });
  app.get('/api/documents/:id/download', (req, res, next) => {
    const { row, file } = ownedFile(requireUser(req).id, req.params.id);
    res.type('application/octet-stream');
    res.download(file, row.name, { cacheControl: false }, (error) => {
      if (error) next(hasCode(error, 'ENOENT') ? httpError(404, '本地文档文件不存在') : error);
    });
  });
  app.get('/api/documents/:id/inline', (req, res) => {
    const { row, file } = ownedFile(requireUser(req).id, req.params.id);
    const mime = documentInlineType(row.name, readFileSync(file));
    if (!mime) throw httpError(415, '该文件请使用下载或文本预览');
    res.setHeader('X-Frame-Options', 'SAMEORIGIN');
    res.type(mime).sendFile(file, { cacheControl: false });
  });
}
