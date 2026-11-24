import type { ReplyState } from '../../shared/types.ts';
import { now } from '../common/runtime.ts';
import type { BotRun, ReplyReview, ReviewSender, StoredTables, Transport } from '../common/types.ts';
import { publishReplyDocument } from '../documents/cloud.ts';
import { generatedReplyFile } from '../documents/library.ts';
import { sendPersonal } from '../platform/messages.ts';
import { commandForRun } from '../replies/owner-commands.ts';
import { reviewWindow } from '../replies/replies.config.ts';
import { isReviewBot, notifyReviewer, prepareReply, recordDraft } from '../replies/reviews.ts';
import { beginWecomDelivery, sendWecomParts, wecomReplyParts } from '../replies/wecom-delivery.ts';
import { splitWecomReply, wecomPlainText } from '../replies/wecom-text.ts';
import { get, run, transaction, unseal } from '../storage/store.ts';
import { botConfig } from './bots.config.ts';

import type { Connection } from './connections.ts';
import { mayHandleInbound } from './inbound.ts';
import { current, hydrate } from './records.ts';

export async function deliverReply(row: BotRun, connections: ReadonlyMap<string, Connection>, isClosed: () => boolean,
  channel?: { key: string; send: ReviewSender }) {
  const stillPending = () => Boolean(get("SELECT 1 FROM runs WHERE id=? AND reply='pending'", row.id));
  if (!get("SELECT 1 FROM runs WHERE id=? AND reply IN ('pending','awaiting_review')", row.id)) return;
  const record = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND enabled=1 AND version=?', row.bot, row.version);
  const finish = (state: ReplyState, receipt: string | null = null, detail: string | null = null) =>
    run(`UPDATE runs SET reply=?,receipt=?,sent_at=?,error=CASE WHEN state='done' THEN ? ELSE error END
      WHERE id=? AND reply IN ('pending','awaiting_review','sending')`,
      state, receipt, state === 'accepted' ? now() : null, detail, row.id);
  if (!record || now() - row.created > (isReviewBot(record) ? reviewWindow : botConfig.pendingMaxAgeMs)) return finish('canceled');
  const bot = hydrate(record);
  const publish = async () => {
    try { return await prepareReply(row.id, () => publishReplyDocument(bot, row.id)); }
    catch (error) {
      const detail = error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]').slice(0, botConfig.platformErrorLength) : '平台未返回操作结果';
      finish('failed', null, '云文档交付失败。' + detail);
    }
  };
  const command = isReviewBot(bot) && commandForRun(row.id);
  const checkInbound = () => prepareReply(row.id, () => mayHandleInbound(row));
  if (command && command.decision !== 'reply') return finish('canceled');
  if (isReviewBot(bot) && !command) {
    const review = get<ReplyReview>('SELECT * FROM reply_reviews WHERE id=?', row.id);
    if (row.reply === 'awaiting_review' && review?.state === 'queued') {
      if (!channel) return;
      if (!await checkInbound() || isClosed() || !current(bot)) {
        const expired = run("UPDATE reply_reviews SET state='expired' WHERE id=? AND state='queued'", row.id).changes;
        if (expired) finish('canceled');
        return;
      }
      return notifyReviewer(bot, row, channel.key, channel.send);
    }
    if (row.reply !== 'pending') return;
    if (review?.state !== 'approved') {
      transaction(() => {
        if (row.answer) recordDraft(row.id, row.answer);
        run("UPDATE runs SET reply='awaiting_review' WHERE id=? AND reply='pending'", row.id);
      });
      return;
    }
    row.answer = review.draft;
  }
  if (!row.transport || !row.answer) return finish('canceled');
  const transport = unseal<Transport>(row.transport);
  if (bot.platform === 'wecom' && bot.mode === 'personal') {
    const file = generatedReplyFile(row.id);
    const fileDetail = file ? 'Word 成品已保存在工作台。企微本人接口仅支持文字，原生附件未发送。' : null;
    const previousParts = wecomReplyParts(row.id);
    if (previousParts.length && previousParts.every((part) => part.state === 'accepted')) {
      return finish('accepted', previousParts.at(-1)?.receipt, previousParts.some((part) => part.document_id) ? null : fileDetail);
    }
    if (!await checkInbound() || isClosed() || !current(bot) || !stillPending()) return finish('canceled');
    let proposed: string[] = [];
    if (!previousParts.length) {
      const link = await publish();
      if (link === undefined || !stillPending()) return;
      if (link && !await checkInbound()) return finish('canceled');
      proposed = splitWecomReply(wecomPlainText(row.answer), link);
      if (!proposed.length) return finish('canceled');
    }
    if (isClosed() || !current(bot)) return finish('canceled');
    if (!beginWecomDelivery(row.id, proposed)) return;
    try {
      finish('accepted', await sendWecomParts(bot, row.target, row.id, () => !isClosed() && Boolean(current(bot)) &&
        Boolean(get("SELECT 1 FROM runs WHERE id=? AND reply='sending'", row.id))), fileDetail);
    }
    catch (error) {
      finish(error instanceof Error && 'canceled' in error ? 'canceled' :
        error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown', null,
        error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]').slice(0, botConfig.platformErrorLength) : null);
    }
    return;
  }
  let send: () => Promise<string | null>;
  if (bot.mode === 'personal') {
    if (!await checkInbound()) return finish('canceled');
    send = () => sendPersonal(bot, row.target, replyText, row.id);
  } else {
    const connection = connections.get(bot.id);
    const client = connection?.client;
    if (connection?.bot.version !== row.version || !client) return;
    send = () => client.send(row.target, replyText, row.id, transport);
  }
  if (isClosed() || !current(bot) || !stillPending()) return finish('canceled');
  const documentLink = await publish();
  if (documentLink === undefined || !stillPending()) return;
  if (documentLink && bot.mode === 'personal' && !await checkInbound()) return finish('canceled');
  if (isClosed() || !current(bot)) return finish('canceled');
  const suffix = documentLink ? '\n\n' + documentLink : '';
  const answer = isReviewBot(bot) ? wecomPlainText(row.answer) : row.answer;
  const body = Array.from(answer).slice(0, botConfig.appReplyLength - Array.from(suffix).length).join('');
  const replyText = body + suffix;
  if (!run("UPDATE runs SET reply='sending',reply_text=?,updated=? WHERE id=? AND reply='pending'", replyText, now(), row.id).changes) return;
  try { finish('accepted', await send()); }
  catch (error) { finish(error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown'); }
}
