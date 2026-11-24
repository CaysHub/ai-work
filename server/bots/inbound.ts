import { chatLimits } from '../../shared/config.ts';
import { now } from '../common/runtime.ts';
import type { AgentRun, BotRun, InboundMessage, LiveBot, StoredTables, Transport } from '../common/types.ts';
import { history } from '../platform/messages.ts';
import { reviewWindow } from '../replies/replies.config.ts';
import { isReviewBot } from '../replies/reviews.ts';
import { pairWecomFiles } from '../replies/wecom-files.ts';
import { all, createChat, enqueue, get, run, transaction, unseal } from '../storage/store.ts';
import { botConfig } from './bots.config.ts';

import { current, hydrate } from './records.ts';

export function groupMentionPattern(name: string) {
  const literal = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?<![\\w@＠<.])[@＠]${literal}(?=$|[\\s\\u200b,，、。；;！？!?：:）)】]|\\.(?=\\s|$))`, 'u');
}
export function accept(bot: LiveBot, target: string | undefined, message: InboundMessage, transport: Transport, title = target) {
  const { id: messageId, text } = message;
  if (!target || !messageId || !text?.trim() || (bot.mode === 'bot' && bot.settings.targets.length &&
    !bot.settings.targets.some((item) => item.id === target))) return;
  transaction(() => {
    const key = bot.platform === 'wecom' && bot.mode === 'personal' ? JSON.stringify([target, messageId]) : messageId;
    if (!current(bot) || get('SELECT 1 FROM inbox WHERE bot=? AND version=? AND message IN (?,?)', bot.id, bot.version, key, messageId)) return;
    let chat = get<{ id: string }>('SELECT id FROM chats WHERE bot=? AND target=? AND version=?', bot.id, target, bot.version)?.id;
    if (!chat) {
      chat = createChat(bot.owner, bot.name + ' · ' + title, bot.platform);
      run('UPDATE chats SET bot=?,target=?,version=? WHERE id=?', bot.id, target, bot.version, chat);
    }
    const id = enqueue(chat, text.slice(0, chatLimits.prompt), bot.settings.skills, { reply: 'waiting', transport });
    run('INSERT INTO inbox VALUES (?,?,?,?)', bot.id, bot.version, key, id);
    if (bot.platform === 'wecom' && transport.channel === 'personal' && message.sender) {
      pairWecomFiles(bot, target, message.sender, transport.sourceTime, id);
    }
  });
}
export async function mayHandleInbound(row: AgentRun | BotRun) {
  const record = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND enabled=1 AND version=?', row.bot, row.version);
  if (!record || now() - row.created > (isReviewBot(record) ? reviewWindow : botConfig.pendingMaxAgeMs)) return false;
  if (record.mode !== 'personal') return true;
  try {
    const bot = hydrate(record);
    if (bot.secrets.refreshing || !row.target || !row.transport) return false;
    const transport = unseal<Transport>(row.transport);
    if (transport.channel !== 'personal') return false;
    const previous = all<{ receipt: string | null; answer: string | null }>(`SELECT receipt,COALESCE(reply_text,answer) AS answer FROM runs WHERE chat=? AND reply='accepted' AND COALESCE(sent_at,created)>=?`,
      row.chat, transport.sourceTime - botConfig.historyClockSkewMs);
    if (bot.platform === 'wecom') previous.push(...all<{ receipt: string | null; answer: string }>(`
      SELECT p.receipt,p.text AS answer FROM wecom_reply_parts p JOIN runs r ON r.id=p.run_id
      WHERE r.chat=? AND p.state='accepted' AND p.accepted>=?`, row.chat, transport.sourceTime - botConfig.historyClockSkewMs));
    const latest = await history(bot, row.target, transport.sourceTime, now(), botConfig.historyVerificationPages);
    const employee = bot.settings.employeeId;
    const manual = latest.some((item) => !item.system && item.sender === employee &&
      !previous.some((old) => old.receipt === item.id || (bot.platform === 'wecom' &&
        old.answer === item.text)));
    return !manual && latest.some((item) => item.id === transport.sourceId) && Boolean(current(bot));
  } catch {
    return false;
  }
}
