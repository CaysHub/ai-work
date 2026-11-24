import type { Express } from 'express';
import { z } from 'zod';
import { replyList, replyReferenceLength } from '../../shared/config.ts';
import type { ReplyPage, ReplyRecord } from '../../shared/types.ts';
import { requireUser } from '../account/auth.ts';
import { httpError, now } from '../common/runtime.ts';
import type { BotRun, LiveBot, ReplyReview, ReviewSender, StoredTables } from '../common/types.ts';
import { withPlatformSignal } from '../platform/http.ts';
import { limitWecomText } from '../platform/messages.ts';
import { all, get, run, transaction } from '../storage/store.ts';
import { replyConfig, reviewWindow } from './replies.config.ts';
import { wecomPlainText } from './wecom-text.ts';

export const isReviewBot = (bot?: Pick<LiveBot, 'platform' | 'mode'>) =>
  bot?.mode === 'personal';

const cancelable = `(r.reply IN ('waiting','awaiting_review','pending') OR (r.reply='sending' AND EXISTS(
  SELECT 1 FROM wecom_reply_parts WHERE run_id=r.id AND state IN ('pending','failed'))))`;
const selected = `SELECT r.id,r.chat,r.prompt,r.answer,r.reply,r.receipt,r.reply_text,r.sent_at,r.created,r.error,
  ${cancelable} AS can_cancel,
  (SELECT count(*) FROM wecom_reply_parts WHERE run_id=r.id AND state='accepted') AS sent_parts,
  c.title,c.source,c.target,c.bot,c.version,c.owner,v.state AS review_state,v.draft,v.notified,v.decided
  FROM runs r JOIN chats c ON c.id=r.chat LEFT JOIN reply_reviews v ON v.id=r.id`;
export function recordDraft(id: string, answer: string) {
  run("INSERT OR IGNORE INTO reply_reviews (id,state,draft,created) VALUES (?,'queued',?,?)",
    id, Array.from(answer.trim()).slice(0, replyConfig.draftLength).join(''), now());
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
export async function notifyReviewer(bot: LiveBot, row: Pick<BotRun, 'id' | 'prompt'>, channel: string, send: ReviewSender, requested = false) {
  const review = get<Pick<ReplyReview, 'draft' | 'state'>>('SELECT draft,state FROM reply_reviews WHERE id=?', row.id);
  if (!review) return;
  const { draft } = review;
  const id = row.id.slice(0, replyReferenceLength);
  const wecom = bot.platform === 'wecom';
  const platform = wecom ? '企微' : bot.platform === 'dingtalk' ? '钉钉' : '飞书';
  const header = '【' + platform + '接管待办 ' + id + '】\n' + Array.from(row.prompt).slice(0, replyConfig.promptPreviewLength).join('') + '\n\n';
  const footer = '\n\n可以直接说「按这个发」「简短一点再发」「这条不用回」。有多个待办时，发「接管 ' + id + '」加上你的要求。';
  const available = replyConfig.noticeMaxBytes - Buffer.byteLength(header + footer);
  const plain = wecomPlainText(draft);
  const body = wecom ? limitWecomText(plain, available) : bot.platform === 'dingtalk'
    ? Array.from(plain).slice(0, replyConfig.dingNoticeMaxCharacters - Array.from(header + footer).length).join('') : plain;
  const text = header + body + footer;
  if (!requested && review.state !== 'queued') return;
  if (!['queued', 'failed', 'unknown', 'ready'].includes(review.state)) return;
  if (!run("UPDATE reply_reviews SET state='notifying',notice_channel=? WHERE id=? AND state=?", channel, row.id, review.state).changes) return;
  try {
    const started = now();
    const receipt = await send(text, 'draft-' + row.id);
    run("UPDATE reply_reviews SET state='ready',notified=?,notice_receipt=? WHERE id=? AND state='notifying'",
      started, receipt, row.id);
  } catch (error) {
    console.warn(platform + '待办通知发送失败', row.id, error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]') : '');
    run("UPDATE reply_reviews SET state=? WHERE id=? AND state='notifying'", requested && review.state === 'ready' ? 'ready' :
      error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown', row.id);
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
      q: z.string().trim().max(replyList.queryLength).default(''),
      state: z.enum(['all', 'review', 'accepted', 'pending', 'rejected', 'skipped', 'other']).default('all'),
      page: z.coerce.number().int().min(1).max(replyConfig.largestPage).default(1),
      botId: z.string().trim().min(1).max(replyConfig.searchBotIdLength).default('all'),
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
      rows: all<ReplyRecord>(selected + ' ' + where + ' ORDER BY r.created DESC,r.id DESC LIMIT ? OFFSET ?', ...args, replyList.pageSize, (page - 1) * replyList.pageSize),
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
      text: z.string().trim().min(1).refine((value) => Array.from(value).length <= replyConfig.draftLength, '回复最多 ' + replyConfig.draftLength + ' 字符').optional(),
    }).parse(req.body);
    transaction(() => {
      const row = get<ReplyRecord>(selected + ' WHERE r.id=? AND c.owner=? AND v.id IS NOT NULL', req.params.id, requireUser(req).id);
      if (!row) throw httpError(404, '找不到待确认回复');
      if (['wecom', 'feishu', 'dingtalk'].includes(row.source)) throw httpError(409, '请在接管机器人私聊中处理此待办');
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
