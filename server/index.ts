import express from 'express';
import type { ErrorRequestHandler } from 'express';
import type { BotSettings, RunProgress, ToolEvent } from '../shared/types.ts';
import type { RunRow } from './types.ts';
import multer from 'multer';
import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { config, hasCode, httpError, rootDir, now, parse } from './config.ts';
import { installAuth, requireUser } from './auth.ts';
import { all, createChat, enqueue, get, owned, run, transaction } from './store.ts';
import { listFiles, readText, skillRows, uploadSkill, workspace, writeText } from './files.ts';
import { installSchedules, promptField, skillsField, tickSchedules } from './schedules.ts';
import { installBots, openBots } from './bots.ts';
import { modelReady, openAgent } from './agent.ts';
import { installAuthorizations, installFeishuCallback } from './authorization.ts';
import { cancelReply, installReplies } from './replies.ts';
import { attachDocuments, chatDocumentLinks, documentIdsField, importWorkspaceDocuments, installDocumentLibrary, saveWorkspaceUpload } from './document-library.ts';

const app = express();
app.disable('x-powered-by');
app.use((_req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  next();
});
app.use('/api', (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
app.use(express.json({ limit: '3mb' }));
app.get('/api/health', (_req, res) => res.json({ ok: true }));
installFeishuCallback(app);
installAuth(app);
app.get('/api/settings', (_req, res) => res.json({ model: config.model.id, ready: modelReady(), documentMaxBytes: config.documentMaxBytes }));

const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 8 * 1024 * 1024, files: 1, fields: 0 } });
app.get('/api/skills', (req, res) => res.json(all('SELECT id,name,description,created FROM skills WHERE owner=? AND archived=0 ORDER BY created DESC', requireUser(req).id)));
app.post('/api/skills', upload.single('file'), (req, res) => res.status(201).json(uploadSkill(requireUser(req).id, req.file)));
app.delete('/api/skills/:id', (req, res) => {
  const skill = owned('skills', requireUser(req).id, req.params.id);
  const inUse = all<{ skills: string }>('SELECT skills FROM schedules WHERE owner=? AND enabled=1', requireUser(req).id).some((item) => parse<string[]>(item.skills).includes(skill.id)) ||
    all<{ settings: string }>('SELECT settings FROM bots WHERE owner=? AND enabled=1', requireUser(req).id).some((item) => parse<BotSettings>(item.settings).skills.includes(skill.id));
  if (inUse) throw httpError(409, '请先在已启用的定时任务或机器人连接中移除此 Skill');
  run('UPDATE skills SET archived=1 WHERE id=?', skill.id);
  res.sendStatus(204);
});
app.get('/api/chats', (req, res) => res.json(all('SELECT * FROM chats WHERE owner=? AND archived=? ORDER BY updated DESC', requireUser(req).id, req.query.archived === 'true' ? 1 : 0)));
const title = z.string().trim().min(1).max(160);
app.post('/api/chats', (req, res) => {
  const id = createChat(requireUser(req).id, title.default('新会话').parse(req.body?.title));
  res.status(201).json(owned('chats', requireUser(req).id, id));
});
app.get('/api/chats/:id', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  const links = chatDocumentLinks(chat.owner, chat.id);
  res.json({ ...chat, runs: all<RunRow>(`SELECT id,prompt,skills,state,answer,error,created,updated,tool_log,progress_log,reply,reply_text,sent_at FROM runs
    WHERE chat=? ORDER BY created,rowid`, chat.id).map((row) => ({ ...row, skills: parse<string[]>(row.skills), tool_log: parse<ToolEvent[]>(row.tool_log),
      progress_log: parse<RunProgress[]>(row.progress_log),
      attachments: links.get(row.id)?.attachments ?? [], documents: links.get(row.id)?.documents ?? [],
    })) });
});
app.patch('/api/chats/:id', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  const change = z.object({ title: title.optional(), archived: z.boolean().optional() }).parse(req.body);
  run('UPDATE chats SET title=?,archived=?,updated=? WHERE id=?', change.title ?? chat.title,
    Number(change.archived ?? chat.archived), now(), chat.id);
  res.json(owned('chats', requireUser(req).id, chat.id));
});
app.post('/api/chats/:id/runs', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  if (chat.archived || chat.bot) throw httpError(409, '此会话不可从网页发送消息');
  const input = z.object({ prompt: promptField, skills: skillsField, documents: documentIdsField, requestId: z.uuid().default(randomUUID) }).parse(req.body);
  skillRows(requireUser(req).id, input.skills);
  const id = transaction(() => {
    const existing = get('SELECT chat FROM runs WHERE id=?', input.requestId);
    if (existing && existing.chat !== chat.id) throw httpError(409, '请求 ID 冲突');
    if (existing) return input.requestId;
    if (chat.title === '新会话') run('UPDATE chats SET title=? WHERE id=?', Array.from(input.prompt).slice(0, 42).join(''), chat.id);
    const id = enqueue(chat.id, input.prompt, input.skills, { id: input.requestId });
    attachDocuments(chat.owner, chat.id, id, input.documents);
    return id;
  });
  res.status(202).json({ id });
});
app.get('/api/chats/:id/files', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  try { res.json(listFiles(workspace(chat.id), z.string().optional().parse(req.query.path))); }
  catch (error) { if (hasCode(error, 'ENOENT')) return res.json([]); throw error; }
});
app.get('/api/chats/:id/file', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  const path = z.string().parse(req.query.path);
  try {
    const content = readText(workspace(chat.id), path);
    if (req.query.download === '1') {
      res.attachment(path.split(/[\\/]/).at(-1)).type('text/plain').send(content);
    } else res.json({ path, content });
  } catch (error) {
    if (hasCode(error, 'ENOENT')) throw httpError(404, '文件不存在');
    throw error;
  }
});
app.put('/api/chats/:id/file', (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  if (chat.archived || chat.bot) throw httpError(409, '当前会话不可上传文件');
  const { path, content } = z.object({ path: z.string(), content: z.string() }).parse(req.body);
  if (Buffer.byteLength(content) > config.documentMaxBytes) throw httpError(413, '文档超过上传限制');
  importWorkspaceDocuments(chat.owner, chat.id);
  mkdirSync(workspace(chat.id), { recursive: true });
  writeText(workspace(chat.id), path, content);
  saveWorkspaceUpload(chat, path, content);
  res.sendStatus(204);
});
const agent = openAgent();
app.post('/api/chats/:id/runs/:run/cancel', async (req, res) => {
  const chat = owned('chats', requireUser(req).id, req.params.id);
  if (get('SELECT 1 FROM chats WHERE id=? AND bot IS NOT NULL', chat.id)) {
    if (!get('SELECT 1 FROM runs WHERE id=? AND chat=?', req.params.run, chat.id)) throw httpError(404, '找不到会话任务');
    cancelReply(chat.owner, req.params.run);
  }
  await agent.cancel(chat.id, req.params.run);
  res.sendStatus(204);
});
installSchedules(app);
installBots(app);
installReplies(app, agent.cancel);
installDocumentLibrary(app);
installAuthorizations(app);
app.use('/api', () => { throw httpError(404, '接口不存在'); });
app.use(express.static(resolve(rootDir, 'dist')));
app.get('/', (_req, res) => res.sendFile(resolve(rootDir, 'dist/index.html')));
const handleError: ErrorRequestHandler = (error: unknown, req, res, next) => {
  if (res.headersSent) return next(error);
  if (error instanceof z.ZodError) return res.status(400).json({ error: '请检查输入字段：' + error.issues.map((item) => item.path.join('.')).join('、') });
  if (error instanceof multer.MulterError) {
    const limit = req.path.startsWith('/api/documents') ? config.documentMaxBytes / (1024 * 1024) : 8;
    return res.status(error.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ error: '每次请求允许上传一个不超过 ' + limit + ' MB 的文件' });
  }
  const status = error instanceof Error && 'status' in error && typeof error.status === 'number' ? error.status : 500;
  if (status >= 500) console.error(error);
  res.status(status).json({ error: status < 500 && error instanceof Error ? error.message : '服务内部错误' });
};
app.use(handleError);

const bots = openBots();
const safe = (operation: () => void) => { try { operation(); } catch (error) { console.error(error); } };
const timers = [
  setInterval(() => safe(agent.tick), 750),
  setInterval(() => safe(tickSchedules), 3000),
  setInterval(() => safe(bots.tick), 2000),
];
const server = app.listen(config.port, config.host, () => {
  const address = server.address();
  if (address && typeof address !== 'string') console.log('AI 工作台已启动 http://' + config.host + ':' + address.port);
});
let closing = false;
async function close() {
  if (closing) return;
  closing = true;
  timers.forEach(clearInterval);
  bots.close();
  server.close();
  await agent.close();
}
process.on('SIGINT', close);
process.on('SIGTERM', close);
