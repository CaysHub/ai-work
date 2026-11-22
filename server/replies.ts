import type { Express } from 'express';
import type { ReplyPage, ReplyRecord } from '../shared/types.ts';
import type { BotRun, LiveBot, ReplyReview, StoredTables } from './types.ts';
import { requireUser } from './auth.ts';
import { z } from 'zod';
import { httpError, now } from './config.ts';
import { all, get, run, transaction } from './store.ts';
import { createFeishuMessage, limitWecomText, sendPersonal, withPlatformSignal } from './platform.ts';
import { wecomPlainText } from './wecom-text.ts';

import { reviewWindow } from './wecom-commands.ts';
export { reviewWindow };
export const isReviewBot = (bot?: Pick<LiveBot, 'platform' | 'mode'>) =>
  bot?.mode === 'personal';

export function reviewerTarget(bot: LiveBot) {
  if (bot.platform === 'wecom') return { id: bot.settings.employeeId, since: bot.since };
  if (bot.platform === 'dingtalk') {
    const id = bot.settings.reviewConversation;
    return id ? { id, since: bot.since } : undefined;
  }
  return get<{ id: string; since: number }>('SELECT target AS id,since FROM feishu_review_chats WHERE bot=? AND version=?', bot.id, bot.version);
}
const cancelable = `(r.reply IN ('waiting','awaiting_review','pending') OR (r.reply='sending' AND EXISTS(
  SELECT 1 FROM wecom_reply_parts WHERE run_id=r.id AND state IN ('pending','failed'))))`;
const selected = `SELECT r.id,r.chat,r.prompt,r.answer,r.reply,r.receipt,r.reply_text,r.sent_at,r.created,r.error,
  ${cancelable} AS can_cancel,
  (SELECT count(*) FROM wecom_reply_parts WHERE run_id=r.id AND state='accepted') AS sent_parts,
  c.title,c.source,c.target,c.bot,c.version,c.owner,v.state AS review_state,v.draft,v.notified,v.decided
  FROM runs r JOIN chats c ON c.id=r.chat LEFT JOIN reply_reviews v ON v.id=r.id`;
export function recordDraft(id: string, answer: string) {
  run("INSERT OR IGNORE INTO reply_reviews (id,state,draft,created) VALUES (?,'queued',?,?)",
    id, Array.from(answer.trim()).slice(0, 2000).join(''), now());
}
export function recoverReviews() {
  run("UPDATE reply_reviews SET state='unknown' WHERE state='notifying'");
}
export function expireReviews() {
  transaction(() => {
    run(`UPDATE reply_reviews AS v SET state='expired'
      WHERE v.state IN ('queued','ready','failed','unknown') AND EXISTS (
        SELECT 1 FROM runs r JOIN chats c ON c.id=r.chat
        LEFT JOIN bots b ON b.id=c.bot AND b.enabled=1 AND b.version=c.version
        WHERE r.id=v.id AND (r.created<=? OR b.id IS NULL)
      )`, now() - reviewWindow);
    run(`UPDATE runs SET reply='canceled' WHERE reply='awaiting_review' AND id IN
      (SELECT id FROM reply_reviews WHERE state='expired')`);
  });
}
export async function notifyReviewer(bot: LiveBot, row: BotRun) {
  const review = get<Pick<ReplyReview, 'draft'>>('SELECT draft FROM reply_reviews WHERE id=?', row.id);
  if (!review) return;
  const { draft } = review;
  const id = row.id.slice(0, 8);
  const wecom = bot.platform === 'wecom';
  const platform = wecom ? '企微' : bot.platform === 'dingtalk' ? '钉钉' : '飞书';
  const header = '【' + platform + '接管待办 ' + id + '】\n' + Array.from(row.prompt).slice(0, 140).join('') + '\n\n';
  const footer = wecom ? '\n\n可直接输入回复正文。待办多于一条时，发送「接管 ' + id + '」并附上正文或处理要求。发送「忽略 ' + id + '」跳过。' :
    '\n\n可以直接说「按这个发」「简短一点再发」「这条不用回」。有多个待办时，发「接管 ' + id + '」加上你的要求。';
  const available = 2000 - Buffer.byteLength(header + footer);
  const plain = wecomPlainText(draft);
  const text = header + (wecom ? limitWecomText(plain, available) : plain) + footer;
  if (!run("UPDATE reply_reviews SET state='notifying' WHERE id=? AND state='queued'", row.id).changes) return;
  try {
    const started = now();
    const target = reviewerTarget(bot);
    if (bot.platform !== 'feishu' && !target) throw new Error('本人接管会话尚未配置');
    const message = bot.platform === 'feishu' ?
      await createFeishuMessage(bot.secrets.userToken, bot.settings.employeeId, text, 'draft-' + row.id, 'open_id') :
      { id: await sendPersonal(bot, target?.id ?? '', text, row.id), chat: undefined };
    if (bot.platform === 'feishu' && !message.chat?.startsWith('oc_')) throw new Error('飞书未返回本人会话 ID');
    transaction(() => {
      if (message.chat) run('INSERT OR IGNORE INTO feishu_review_chats VALUES (?,?,?,?)', bot.id, bot.version, message.chat, started);
      run("UPDATE reply_reviews SET state='ready',notified=?,notice_receipt=? WHERE id=? AND state='notifying'",
        wecom ? now() : started, message.id, row.id);
    });
  } catch (error) {
    console.warn(platform + '待办通知发送失败', row.id, error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]') : '');
    run("UPDATE reply_reviews SET state=? WHERE id=? AND state='notifying'", error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown', row.id);
  }
}
export const userCanceledReply = '已手动取消本次处理';
const preparations = new Map<string, AbortController>();

export async function prepareReply<T>(id: string, work: () => Promise<T>): Promise<T> {
  const controller = new AbortController();
  preparations.set(id, controller);
  try {
    return await withPlatformSignal(controller.signal, work);
  } finally {
    preparations.delete(id);
  }
}

export function cancelReply(owner: string, id: string) {
  const chat = transaction(() => {
    const row = get<Pick<ReplyRecord, 'id' | 'chat' | 'reply' | 'error' | 'can_cancel'>>(
      `SELECT r.id,r.chat,r.reply,r.error,${cancelable} AS can_cancel FROM runs r
        JOIN chats c ON c.id=r.chat WHERE r.id=? AND c.owner=? AND c.bot IS NOT NULL`, id, owner);
    if (!row) throw httpError(404, '找不到回复记录');
    if (row.reply === 'canceled' && row.error === userCanceledReply) return row.chat;
    if (!row.can_cancel) throw httpError(409, '这条记录当前无法取消');
    const time = now();
    run(`UPDATE runs SET state=CASE WHEN state IN ('queued','running') THEN 'canceled' ELSE state END,
      reply='canceled',error=?,updated=? WHERE id=?`, userCanceledReply, time, id);
    run(`UPDATE reply_reviews SET state='canceled',decided=? WHERE id=?
      AND state IN ('queued','notifying','ready','failed','unknown','approved')`, time, id);
    run('UPDATE wecom_commands SET feedback=1 WHERE id=?', id);
    return row.chat;
  });
  preparations.get(id)?.abort(new Error(userCanceledReply));
  return chat;
}

export function installReplies(app: Express, stopRun: (chat: string, id: string) => Promise<void>) {
  app.get('/api/replies', (req, res) => {
    const { q, state, page, botId } = z.object({
      q: z.string().trim().max(200).default(''),
      state: z.enum(['all', 'review', 'accepted', 'pending', 'rejected', 'skipped', 'other']).default('all'),
      page: z.coerce.number().int().min(1).max(100000).default(1),
      botId: z.string().trim().min(1).max(100).default('all'),
    }).parse(req.query);
    const condition = {
      all: '1=1', review: "r.reply='awaiting_review'", accepted: "r.reply='accepted'",
      pending: "r.reply IN ('waiting','pending','sending')", rejected: "v.state='rejected'",
      skipped: "r.reply='skipped'",
      other: "r.reply IN ('failed','canceled','unknown') AND COALESCE(v.state,'')<>'rejected'",
    }[state];
    const where = `WHERE c.owner=? AND c.bot IS NOT NULL AND (?='all' OR c.bot=?) AND ${condition} AND
      (?='' OR instr(lower(c.title),lower(?)) OR instr(lower(r.prompt),lower(?)) OR
      instr(lower(COALESCE(r.reply_text,v.draft,r.answer,'')),lower(?)))`;
    const owner = requireUser(req).id;
    const args = [owner, botId, botId, q, q, q, q];
    const total = get<{ total: number }>(`SELECT count(*) AS total FROM runs r JOIN chats c ON c.id=r.chat
      LEFT JOIN reply_reviews v ON v.id=r.id ${where}`, ...args)?.total ?? 0;
    const bots = all<ReplyPage['bots'][number]>(`
      SELECT id,name,platform FROM bots WHERE owner=?
      UNION ALL
      SELECT c.bot AS id, MAX(CASE WHEN instr(c.title,' · ')>0
        THEN substr(c.title,1,instr(c.title,' · ')-1) ELSE c.title END) AS name, MIN(c.source) AS platform
      FROM chats c WHERE c.owner=? AND c.bot IS NOT NULL AND c.source IN ('wecom','feishu','dingtalk')
        AND NOT EXISTS(SELECT 1 FROM bots WHERE id=c.bot) AND EXISTS(SELECT 1 FROM runs WHERE chat=c.id)
      GROUP BY c.bot
      ORDER BY name,id`, owner, owner);
    res.json({
      total, bots,
      rows: all<ReplyRecord>(selected + ' ' + where + ' ORDER BY r.created DESC,r.id DESC LIMIT 20 OFFSET ?', ...args, (page - 1) * 20),
    } satisfies ReplyPage);
  });
  app.get('/api/replies/:id', (req, res) => {
    const row = get<ReplyRecord>(selected + ' WHERE r.id=? AND c.owner=? AND c.bot IS NOT NULL', req.params.id, requireUser(req).id);
    if (!row) throw httpError(404, '找不到回复记录');
    const document = get<NonNullable<ReplyRecord['document']>>('SELECT title,content,url FROM reply_documents WHERE id=?', row.id);
    res.json({ ...row, document });
  });
  app.post('/api/replies/:id/cancel', async (req, res) => {
    const id = req.params.id;
    const chat = cancelReply(requireUser(req).id, id);
    await stopRun(chat, id);
    res.sendStatus(204);
  });
  app.post('/api/replies/:id/decision', (req, res) => {
    const { action, text } = z.object({
      action: z.enum(['approve', 'reject', 'retry']),
      text: z.string().trim().min(1).refine((value) => Array.from(value).length <= 2000, '回复最多 2000 字符').optional(),
    }).parse(req.body);
    transaction(() => {
      const row = get<ReplyRecord>(selected + ' WHERE r.id=? AND c.owner=? AND v.id IS NOT NULL', req.params.id, requireUser(req).id);
      if (!row) throw httpError(404, '找不到待确认回复');
      if (row.source === 'wecom') throw httpError(409, '请在本人企微会话处理此待办');
      if (row.source === 'feishu') throw httpError(409, '请在本人飞书会话处理此待办');
      if (row.source === 'dingtalk') throw httpError(409, '请在本人钉钉会话处理此待办');
      if (row.reply !== 'awaiting_review') throw httpError(409, '这条回复已处理');
      if (action === 'reject') {
        run("UPDATE reply_reviews SET state='rejected',decided=? WHERE id=?", now(), row.id);
        run("UPDATE runs SET reply='canceled',updated=? WHERE id=?", now(), row.id);
        return;
      }
      if (now() - row.created >= reviewWindow ||
        !isReviewBot(get<Pick<StoredTables['bots'], 'platform' | 'mode'>>('SELECT platform,mode FROM bots WHERE id=? AND owner=? AND version=? AND enabled=1', row.bot, row.owner, row.version))) {
        throw httpError(409, '该回复已过期或企微接管配置已变更');
      }
      if (action === 'retry' && row.review_state === 'failed') {
        run("UPDATE reply_reviews SET state='queued' WHERE id=?", row.id);
        return;
      }
      if (action !== 'approve' || row.review_state !== 'ready') throw httpError(409, '本人预览送达后才能确认发送');
      run("UPDATE reply_reviews SET state='approved',draft=?,decided=? WHERE id=?", text ?? row.draft, now(), row.id);
      run("UPDATE runs SET reply='pending',updated=? WHERE id=?", now(), row.id);
    });
    res.sendStatus(204);
  });
}
