import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import type { LiveBot } from '../common/types.ts';
import { lark } from './http.ts';
import { dingCall, wecomCall } from './mcp.ts';
import { platformConfig } from './platform.config.ts';
export type HistoryMessage = { id: string; sender?: string; time: number; text: string; mentions: string[]; textMessage?: boolean; system?: boolean; replyTo?: string;
  file?: { mediaId: string; name?: string } };
type RawMessage = Omit<HistoryMessage, 'id' | 'mentions'> & { id?: string; mentions?: string[] };
type PageState = { has_more?: boolean; errcode?: number; cursor?: string; next_cursor?: string };
type FeishuMessage = {
  message_id?: string; create_time?: string; msg_type?: string; deleted?: boolean;
  parent_id?: string; root_id?: string;
  sender?: { id?: string; sender_type?: string }; body?: { content?: string }; mentions?: { id?: string | { open_id?: string } }[];
};
type WecomMessage = {
  msgid?: string; message_id?: string; userid?: string; send_time?: string; msg_type?: string;
  text?: { content?: string; mentioned_list?: string[] }; link?: { title?: string; url?: string };
  file?: { media_id?: string; file_name?: string; filename?: string };
  mentioned_list?: string[]; mentioned_user_ids?: string[];
};
type DingMessage = {
  createTime?: number; sendType?: string; messageAiSendFlag?: boolean; openMessageId?: string; openConversationId?: string;
  senderOpenDingTalkId?: string; atOpenDingTalkIds?: string[]; msgType?: string; content?: unknown;
};
type DingReceipt = { openMessageId?: string; openConversationId?: string; openTaskId?: string; sendStatus?: string };

export function textValue(value: unknown): string {
  if (!value) return '';
  if (typeof value === 'object') {
    const { text, content } = value as { text?: unknown; content?: unknown };
    return String(text ?? content ?? '');
  }
  if (typeof value !== 'string') return String(value);
  try {
    const payload = JSON.parse(value);
    return payload && typeof payload === 'object' ? textValue(payload) : value;
  } catch {
    return value;
  }
}
function epoch(value: unknown) {
  if (/^\d+$/.test(String(value))) return Number(value) * (String(value).length <= 10 ? 1000 : 1);
  return Date.parse(String(value).replace(' ', 'T') + (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}$/.test(String(value)) ? platformConfig.wecomUtcOffset : ''));
}
const china = new Intl.DateTimeFormat('sv-SE', { timeZone: platformConfig.wecomTimeZone, dateStyle: 'short', timeStyle: 'medium' });
export async function wecomGroups(bot: LiveBot, start: number, end: number) {
  const groups = new Map<string, { id: string; name?: string }>();
  const cursors = new Set();
  let cursor: string | undefined;
  for (let page = 0; page < platformConfig.groupPages; page++) {
    const data = await wecomCall<PageState & { chats_count?: number; chats?: { chat_id: string; chat_name?: string }[] }>(bot.secrets.messageMcp, 'msg', 'chat_groups_list', {
      begin_time: china.format(start), end_time: china.format(end), ...(cursor ? { cursor } : {}),
    });
    const rows = data.chats ?? (data.has_more === false && Number(data.errcode) === 0 && Number(data.chats_count) === 0 ? [] : null);
    if (!Array.isArray(rows) || typeof data.has_more !== 'boolean' ||
      rows.some((row) => typeof row.chat_id !== 'string' || !row.chat_id)) throw new Error('企微群聊列表不完整');
    for (const row of rows) groups.set(row.chat_id, { id: row.chat_id, name: row.chat_name });
    if (!data.has_more) return [...groups.values()];
    cursor = data.next_cursor;
    if (!cursor || cursors.has(cursor)) throw new Error('企微群聊分页游标无效');
    cursors.add(cursor);
  }
  throw new Error('企微群聊发现超过分页上限');
}
export async function history(bot: LiveBot, target: string, start: number, end: number, maxPages = platformConfig.historyPages) {
  const { platform, secrets } = bot;
  let cursor: string | undefined;
  const seen = new Set();
  const duplicates = new Map<string, number>();
  const items: HistoryMessage[] = [];
  for (let page = 0; page < maxPages; page++) {
    let messages: RawMessage[] | undefined;
    let next: string | null;
    if (platform === 'feishu') {
      const query = new URLSearchParams({ container_id_type: 'chat', container_id: target,
        start_time: String(Math.floor(start / 1000)), end_time: String(Math.ceil(end / 1000)),
        sort_type: 'ByCreateTimeAsc', page_size: String(platformConfig.historyPageSize), ...(cursor ? { page_token: cursor } : {}) });
      const data = await lark<{ items?: FeishuMessage[]; has_more?: boolean; page_token?: string }>('/im/v1/messages?' + query, { token: secrets.userToken });
      if (typeof data.has_more !== 'boolean') throw new Error('飞书响应缺少分页状态');
      messages = data.items?.map((row) => ({ id: row.message_id, sender: row.sender?.id, time: epoch(row.create_time),
        text: row.msg_type === 'text' ? textValue(row.body?.content) : '',
        textMessage: row.msg_type === 'text',
        replyTo: row.parent_id || row.root_id,
        mentions: row.mentions?.flatMap(({ id }) => typeof id === 'string' ? [id] : id?.open_id ? [id.open_id] : []),
        system: row.sender?.sender_type !== 'user' || row.deleted }));
      next = data.has_more ? data.page_token || 'invalid' : null;
    } else if (platform === 'wecom') {
      const data = await wecomCall<PageState & { messages?: WecomMessage[]; messages_count?: number }>(secrets.messageMcp, 'msg', 'chat_messages_list', {
        chat_id: target, begin_time: china.format(start), end_time: china.format(end), ...(cursor ? { cursor } : {}),
      });
      if (typeof data.has_more !== 'boolean') throw new Error('企微响应缺少分页状态');
      const rows = data.messages ?? (data.has_more === false && Number(data.errcode) === 0 && Number(data.messages_count) === 0 ? [] : null);
      messages = rows?.map((row) => ({ id: row.msgid ?? row.message_id, sender: row.userid, time: epoch(row.send_time),
        text: row.msg_type === 'text' ? textValue(row.text) : row.msg_type === 'link' ?
          [row.link?.title, row.link?.url].filter(Boolean).join('\n') : '',
        textMessage: row.msg_type === 'text',
        mentions: [row.mentioned_list, row.mentioned_user_ids, row.text?.mentioned_list].find((ids) => Array.isArray(ids) && ids.length > 0),
        file: row.msg_type === 'file' && typeof row.file?.media_id === 'string' && row.file.media_id.length > 0
          ? { mediaId: row.file.media_id, name: row.file.file_name ?? row.file.filename } : undefined }));
      next = data.has_more ? data.next_cursor || data.cursor || 'invalid' : null;
    } else {
      const boundary: string = cursor ?? String(end);
      const data = await dingCall<{ messages?: DingMessage[]; hasMore?: boolean; nextCursor?: string | number }>(secrets.userToken, 'list_conversation_message_v2', {
        openconversation_id: target, time: boundary, forward: false, limit: platformConfig.historyPageSize, mark_as_read: false,
      });
      if (typeof data.hasMore !== 'boolean') throw new Error('钉钉响应缺少分页状态');
      messages = data.messages?.map((row) => {
        const time = epoch(row.createTime);
        const system = row.sendType !== 'user' || Boolean(row.messageAiSendFlag);
        if (!system && (!row.openMessageId || target !== row.openConversationId)) throw new Error('钉钉消息身份不完整');
        return { id: row.openMessageId, sender: row.senderOpenDingTalkId, time,
          text: textValue(row.content), textMessage: !row.msgType || row.msgType === 'text', mentions: row.atOpenDingTalkIds, system };
      });
      const dingRows = messages;
      if (dingRows?.some((row, index) => index > 0 && row.time > dingRows[index - 1].time)) throw new Error('钉钉消息顺序无效');
      next = data.hasMore && !messages?.some((row) => row.time < start) ? String(data.nextCursor ?? 'invalid') : null;
      if (next && (!/^\d+$/.test(next) || Number(next) >= Number(boundary))) throw new Error('钉钉分页游标无效');
    }
    if (!Array.isArray(messages) || messages.some((row) => !Number.isFinite(row.time) || (!row.sender && !row.system))) {
      throw new Error('历史消息响应不完整');
    }
    for (const row of messages) {
      if (row.time < start || row.time > end) continue;
      if (!Array.isArray(row.mentions)) row.mentions = [];
      if (!row.id) {
        const fingerprint = createHash('sha256').update(JSON.stringify([target, row.sender, row.time, row.text])).digest('hex');
        const count = (duplicates.get(fingerprint) ?? 0) + 1;
        duplicates.set(fingerprint, count);
        row.id = fingerprint + ':' + count;
      }
      if (!seen.has(row.id)) items.push({ ...row, id: row.id, mentions: row.mentions });
      seen.add(row.id);
    }
    if (!next) return items.sort((a, b) => a.time - b.time);
    if (next === 'invalid' || next === cursor) throw new Error('历史消息分页游标无效');
    cursor = next;
  }
  throw new Error('历史消息超过分页上限');
}
export function limitWecomText(text: string, maxBytes = platformConfig.wecomTextMaxBytes) {
  if (Buffer.byteLength(text) <= maxBytes) return text;
  const parts: string[] = [];
  const ellipsis = '…';
  let remaining = maxBytes - Buffer.byteLength(ellipsis);
  if (remaining < 0) return '';
  for (const character of text) {
    remaining -= Buffer.byteLength(character);
    if (remaining < 0) break;
    parts.push(character);
  }
  return parts.join('') + ellipsis;
}

export async function sendPersonal(bot: LiveBot, target: string, text: string, id: string) {
  if (bot.platform === 'wecom') {
    let receipt: { errcode?: number; msgid?: string; message_id?: string };
    try {
      receipt = await wecomCall(bot.secrets.messageMcp, 'msg', 'message_send', {
        chat_id: target, msg_type: 'text', text: { content: limitWecomText(text) },
      });
    } catch (error) {
      if (error instanceof Error && 'providerRejected' in error && error.providerRejected) Object.assign(error, { rejected: true });
      throw error;
    }
    if (Number(receipt.errcode) !== 0 && !receipt.msgid && !receipt.message_id) throw new Error('企微发送结果未获确认');
    return receipt.msgid ?? receipt.message_id ?? null;
  }
  if (bot.platform === 'feishu') return sendFeishu(bot.secrets.userToken, target, text, id);
  const payload = await dingCall<DingReceipt>(bot.secrets.userToken, 'send_personal_message', {
    openConversationId: target, uuid: id, msgType: 'text', content: JSON.stringify({ content: text }),
  });
  if (payload.openMessageId && payload.openConversationId === target) return payload.openMessageId;
  if (!payload.openTaskId) throw new Error('钉钉回执状态未知');
  for (let attempt = 0; attempt < platformConfig.dingReceiptAttempts; attempt++) {
    await delay(platformConfig.dingReceiptRetryMs);
    const receipt = await dingCall<DingReceipt>(bot.secrets.userToken, 'query_message_send_status', { openTaskId: payload.openTaskId });
    if (receipt.sendStatus === 'SUCCESS' && receipt.openConversationId === target && receipt.openMessageId) return receipt.openMessageId;
    if (receipt.sendStatus === 'FAILED') throw Object.assign(new Error('钉钉明确拒绝发送'), { rejected: true });
  }
  throw new Error('钉钉回执状态未知');
}
export async function createFeishuMessage(token: string | undefined, target: string, text: string, id: string, type: 'chat_id' | 'open_id') {
  const data = await lark<{ message_id?: string; chat_id?: string }>('/im/v1/messages?receive_id_type=' + type, { method: 'POST', token,
    body: { receive_id: target, msg_type: 'text', content: JSON.stringify({ text }), uuid: id } });
  if (!data.message_id) throw new Error('飞书回执状态未知');
  return { id: data.message_id, chat: data.chat_id };
}
export async function sendFeishu(token: string | undefined, target: string, text: string, id: string) {
  return (await createFeishuMessage(token, target, text, id, 'chat_id')).id;
}
