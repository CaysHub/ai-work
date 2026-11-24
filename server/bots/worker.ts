import { now } from '../common/runtime.ts';
import type { BotRun, LiveBot, ReviewSender, StoredTables, Transport } from '../common/types.ts';
import { handleOwnerCommand, notifyCommandResults } from '../replies/owner-commands.ts';
import { reviewWindow } from '../replies/replies.config.ts';
import { expireReviews, isReviewBot, recoverReviews } from '../replies/reviews.ts';
import { recoverWecomDeliveries } from '../replies/wecom-delivery.ts';
import { all, get, run } from '../storage/store.ts';
import { botConfig } from './bots.config.ts';

import type { BotMessage, Connection } from './connections.ts';
import { connectBot } from './connections.ts';
import { deliverReply } from './delivery.ts';
import { accept } from './inbound.ts';
import { pollPersonal } from './polling.ts';
import { current, enabledBots, hydrate, reviewRoute, setStatus } from './records.ts';

export function openBots() {
  const connections = new Map<string, Connection>();
  let closed = false;
  let sending = false;
  recoverWecomDeliveries();
  recoverReviews();
  function reviewSender(personal: LiveBot, route: NonNullable<ReturnType<typeof reviewRoute>>): ReviewSender | undefined {
    const connection = connections.get(route.bot.id);
    if (closed || !connection?.client || connection.bot.version !== route.bot.version ||
      !get("SELECT 1 FROM bots WHERE id=? AND version=? AND enabled=1 AND status='online'", route.bot.id, route.bot.version)) return;
    return async (text, id) => {
      if (closed || connections.get(route.bot.id) !== connection || !current(route.bot)) throw new Error('接管机器人配置已变更');
      const latest = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND version=? AND mode=? AND enabled=1', personal.id, personal.version, 'personal');
      if (!latest || reviewRoute(hydrate(latest), [route.bot])?.key !== route.key) throw new Error('本人接管的机器人绑定已变更');
      return connection.client!.sendReview(route.recipient, text, id);
    };
  }
  function receive(robot: LiveBot, message: BotMessage, target: string | undefined, transport: Transport) {
    if (closed || !current(robot)) return;
    if (message.privateChat && message.id && message.sender && message.text?.trim()) {
      const personalBots = all<StoredTables['bots']>("SELECT * FROM bots WHERE enabled=1 AND mode='personal' AND owner=? AND platform=?",
        robot.owner, robot.platform).map(hydrate);
      const bindings = personalBots.flatMap((personal) => {
        const route = reviewRoute(personal, [robot]);
        return route && route.recipient === message.sender ? [{ personal, route }] : [];
      });
      if (bindings.length) {
        const [{ personal, route }] = bindings;
        const send = bindings.length === 1 ? message.reply ?? reviewSender(personal, route) : undefined;
        const timestamp = message.time && Number.isFinite(message.time) ? message.time : now();
        const time = timestamp < 1e12 ? timestamp * 1000 : timestamp;
        if (send && time >= Math.max(personal.since - botConfig.historyClockSkewMs, now() - reviewWindow)) {
          void handleOwnerCommand(personal, { id: message.id, sender: message.sender, text: message.text, time, mentions: [],
            replyTo: message.replyTo }, route.key, send, message.quotedReview, Boolean(message.reply))
            .catch(() => console.warn('接管机器人指令处理失败'));
        }
        return;
      }
    }
    accept(robot, target, message, transport);
  }
  return {
    tick() {
      const bots = enabledBots();
      for (const [id, connection] of connections) {
        if (bots.some((bot) => bot.id === id && bot.version === connection.bot.version)) continue;
        connections.delete(id);
        clearTimeout(connection.timer);
        connection.client?.close();
      }
      for (const bot of bots) {
        if (connections.has(bot.id)) continue;
        const connection: Connection = { bot };
        connections.set(bot.id, connection);
        const work = async () => {
          if (closed || connections.get(bot.id) !== connection) return;
          try {
            if (bot.mode === 'personal') await pollPersonal(bot, () => closed, reviewSender);
            else {
              connection.client = await connectBot(bot, (message, target, transport) => receive(bot, message, target, transport));
              if (closed || connections.get(bot.id) !== connection) connection.client.close();
            }
          } catch {
            setStatus(bot, 'error', '平台验证或消息拉取失败，请核对权限、凭据和会话 ID');
          } finally {
            if (!closed && connections.get(bot.id) === connection && (bot.mode === 'personal' || !connection.client)) {
              connection.timer = setTimeout(work, botConfig.retryMs);
            }
          }
        };
        void work();
      }
      if (!sending) {
        expireReviews();
        sending = true;
        void (async () => {
          const channels = new Map<string, { key: string; send: ReviewSender }>();
          const bindings = bots.filter(isReviewBot).flatMap((personal) => {
            const route = reviewRoute(personal, bots);
            return route ? [{ personal, route }] : [];
          });
          for (const { personal, route } of bindings) {
            if (bindings.filter((item) => item.route.key === route.key).length !== 1) continue;
            const send = reviewSender(personal, route);
            if (!send) continue;
            channels.set(personal.id, { key: route.key, send });
            run(`UPDATE reply_reviews SET state='queued',notified=NULL,notice_receipt=NULL,notice_channel=NULL
              WHERE state IN ('ready','failed','unknown') AND notice_channel IS NOT ? AND id IN (
                SELECT r.id FROM runs r JOIN chats c ON c.id=r.chat
                WHERE r.reply='awaiting_review' AND c.bot=? AND c.version=?
              )`, route.key, personal.id, personal.version);
          }
          const pending = all<BotRun>(`SELECT r.*,c.bot,c.version,c.target,c.title FROM runs r JOIN chats c ON c.id=r.chat
            LEFT JOIN reply_reviews v ON v.id=r.id
            WHERE r.reply='pending' OR (r.reply='awaiting_review' AND v.state='queued' AND c.bot IN (SELECT value FROM json_each(?)))
            ORDER BY r.created LIMIT ?`, JSON.stringify([...channels.keys()]), botConfig.workerBatchSize);
          for (const row of pending) {
            if (closed) break;
            await deliverReply(row, connections, () => closed, channels.get(row.bot));
          }
          for (const bot of bots.filter(isReviewBot)) {
            if (closed) break;
            const channel = channels.get(bot.id);
            if (channel) await notifyCommandResults(bot, channel.send);
          }
        })().catch((error) => console.error('IM worker:', error.name)).finally(() => { sending = false; });
      }
    },
    close() {
      closed = true;
      for (const connection of connections.values()) {
        clearTimeout(connection.timer);
        connection.client?.close();
      }
      connections.clear();
    },
  };
}
