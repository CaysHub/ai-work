import express from 'express';
import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { z } from 'zod';
import type { RunProgress, ToolEvent } from '../../shared/types.ts';
import { requireUser } from '../account/auth.ts';
import { hasCode, httpError, now, parse } from '../common/runtime.ts';
import type { RunRow } from '../common/types.ts';
import { config } from '../config/env.ts';
import { listFiles, readText, workspace, writeText } from '../files/workspace.ts';
import { skillRows } from '../skills/skills.ts';
import { all, createChat, enqueue, get, owned, run, transaction } from '../storage/store.ts';

import { attachDocuments, chatDocumentLinks, documentIdsField, importWorkspaceDocuments, saveWorkspaceUpload } from '../documents/library.ts';
import { cancelReply } from '../replies/reviews.ts';

import { chatDefaults, chatLimits } from '../../shared/config.ts';
import { chatConfig } from './chat.config.ts';
import { promptField, skillsField } from './fields.ts';

export function installChatRoutes(app: express.Express, cancelRun: (chat: string, id: string) => Promise<void>) {
  app.get('/api/chats', (req, res) => res.json(all('SELECT * FROM chats WHERE owner=? AND archived=? ORDER BY updated DESC', requireUser(req).id, req.query.archived === 'true' ? 1 : 0)));
  const title = z.string().trim().min(1).max(chatLimits.title);
  app.post('/api/chats', (req, res) => {
    const id = createChat(requireUser(req).id, title.default(chatDefaults.title).parse(req.body?.title));
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
      if (chat.title === chatDefaults.title) run('UPDATE chats SET title=? WHERE id=?', Array.from(input.prompt).slice(0, chatConfig.generatedTitleLength).join(''), chat.id);
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
  app.post('/api/chats/:id/runs/:run/cancel', async (req, res) => {
    const chat = owned('chats', requireUser(req).id, req.params.id);
    if (get('SELECT 1 FROM chats WHERE id=? AND bot IS NOT NULL', chat.id)) {
      if (!get('SELECT 1 FROM runs WHERE id=? AND chat=?', req.params.run, chat.id)) throw httpError(404, '找不到会话任务');
      cancelReply(chat.owner, req.params.run);
    }
    await cancelRun(chat.id, req.params.run);
    res.sendStatus(204);
  });
}
