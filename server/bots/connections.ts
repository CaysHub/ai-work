import { randomUUID } from 'node:crypto';
import { now, parse } from '../common/runtime.ts';
import type { InboundMessage, LiveBot, ReviewSender, Transport } from '../common/types.ts';
import { feishuApp, jsonRequest } from '../platform/http.ts';
import { createFeishuMessage, limitWecomText, sendFeishu, textValue } from '../platform/messages.ts';
import { platformEndpoints } from '../platform/platform.config.ts';
import { botConfig } from './bots.config.ts';

import { current, setStatus } from './records.ts';

const logger = { trace() {}, debug() {}, info() {}, warn() {}, error() {} };
export type ConnectionClient = { close: () => void; send: (target: string, text: string, id: string, transport: Transport) => Promise<string | null>;
  sendReview: (recipient: string, text: string, id: string) => Promise<string | null> };
export type Connection = { bot: LiveBot; client?: ConnectionClient; timer?: ReturnType<typeof setTimeout> };
export type BotMessage = InboundMessage & { privateChat: boolean; time?: number; replyTo?: string; quotedReview?: string; reply?: ReviewSender };
export async function connectBot(bot: LiveBot, receive: (message: BotMessage, target: string | undefined, transport: Transport) => void): Promise<ConnectionClient> {
  const { platform, secrets } = bot;
  if (platform === 'wecom') {
    const { default: WeCom } = await import('@wecom/aibot-node-sdk');
    if (!secrets.botId || !secrets.botSecret) throw new Error('企微机器人凭据未配置');
    const client = new WeCom.WSClient({ botId: secrets.botId, secret: secrets.botSecret, logger });
    client.on('authenticated', () => setStatus(bot, 'online'));
    client.on('disconnected', () => setStatus(bot, 'connecting'));
    client.on('error', () => setStatus(bot, 'error', '企微连接异常'));
    client.on('message.text', (frame) => {
      const row = frame.body;
      if (!row) return;
      if (row.aibotid && row.aibotid !== secrets.botId) return;
      const quotedReview = /^【企微接管待办 ([a-f0-9]{8})】/i.exec(row.quote?.text?.content ?? '')?.[1];
      const reply: ReviewSender = async (text) => {
        const result = await client.replyStream(frame, randomUUID(), limitWecomText(text), true);
        return result.headers?.req_id ?? null;
      };
      receive({ id: row.msgid, text: row.text?.content, sender: row.from?.userid, privateChat: row.chattype === 'single',
        time: row.create_time, quotedReview, reply },
        row.chatid ?? row.from?.userid, { channel: 'wecom' });
    });
    if (current(bot)) client.connect();
    return {
      close: () => client.disconnect(),
      send: async (target, text) => {
        const result = await client.sendMessage(target, { msgtype: 'markdown', markdown: { content: text } });
        return result.headers?.req_id ?? null;
      },
      async sendReview(recipient, text) {
        try {
          const result = await client.sendMessage(recipient, { msgtype: 'markdown', markdown: { content: limitWecomText(text) } });
          return result.headers?.req_id ?? null;
        } catch (error) {
          const code = error && typeof error === 'object' && 'errcode' in error ? Number(error.errcode) : 0;
          if (!code) throw error;
          throw Object.assign(new Error(code === botConfig.wecomRateLimitCode ? '企微限制机器人主动发送（' + botConfig.wecomRateLimitCode + '），可在机器人私聊发「接管待办」查看' :
            '企微机器人主动发送失败（' + code + '）'), { rejected: true, rateLimited: code === botConfig.wecomRateLimitCode });
        }
      },
    };
  }
  if (platform === 'feishu') {
    const sdk = await import('@larksuiteoapi/node-sdk');
    if (!secrets.appId || !secrets.appSecret) throw new Error('飞书应用凭据未配置');
    const client = new sdk.WSClient({ appId: secrets.appId, appSecret: secrets.appSecret, logger });
    const eventDispatcher = new sdk.EventDispatcher({ logger }).register({
      'im.message.receive_v1': ({ message, sender }) => {
        if (message?.message_type !== 'text' || sender?.sender_type !== 'user') return;
        receive({ id: message.message_id, text: textValue(message.content), sender: sender.sender_id?.open_id,
          privateChat: message.chat_type === 'p2p', time: Number(message.create_time), replyTo: message.parent_id ?? message.root_id },
        message.chat_id, { channel: 'feishu' });
      },
    });
    const auth = await feishuApp(secrets);
    if (current(bot)) await client.start({ eventDispatcher });
    setStatus(bot, 'online');
    let token = auth;
    let issued = now();
    async function appToken() {
      if (now() - issued > botConfig.feishuTokenRefreshMs) { token = await feishuApp(secrets); issued = now(); }
      return token;
    }
    return {
      close: () => client.close({ force: true }),
      async send(target, text, id) {
        return sendFeishu(await appToken(), target, text, id);
      },
      async sendReview(recipient, text, id) {
        return (await createFeishuMessage(await appToken(), recipient, text, id, 'open_id')).id;
      },
    };
  }
  const { DWClient, TOPIC_ROBOT } = await import('dingtalk-stream');
  if (!secrets.appId || !secrets.appSecret) throw new Error('钉钉应用凭据未配置');
  const client = new DWClient({ clientId: secrets.appId, clientSecret: secrets.appSecret, debug: false });
  client.registerCallbackListener(TOPIC_ROBOT, (frame) => {
    try {
      const row = parse<{ conversationType?: string; msgtype?: string; robotCode?: string; isInAtList?: boolean;
        conversationId?: string; senderStaffId?: string; senderCorpId?: string; chatbotCorpId?: string; msgId?: string;
        createAt?: number; text?: { content?: string } }>(frame.data);
      const group = row.conversationType === '2';
      const sameOrganization = !row.senderCorpId || !row.chatbotCorpId || row.senderCorpId === row.chatbotCorpId;
      if (row.msgtype === 'text' && (!row.robotCode || row.robotCode === secrets.appId) &&
        ((row.conversationType === '1' && sameOrganization) || (group && row.isInAtList !== false))) {
        receive({ id: row.msgId, text: row.text?.content, sender: row.senderStaffId, privateChat: !group, time: row.createAt },
          group ? row.conversationId : row.senderStaffId, { channel: 'dingtalk', group });
      }
      client.socketCallBackResponse(frame.headers.messageId, { success: true });
    } catch { setStatus(bot, 'error', '钉钉消息格式或入队异常'); }
  });
  if (current(bot)) await client.connect();
  setStatus(bot, 'online');
  let token = '';
  let expiry = 0;
  async function sendDingBot(target: string, text: string, group: boolean) {
    const origin = platformEndpoints.dingApi;
    if (now() >= expiry) {
      const auth = await jsonRequest<{ accessToken?: string; expireIn?: number }>(origin + '/oauth2/accessToken', { method: 'POST',
        body: { appKey: secrets.appId, appSecret: secrets.appSecret } });
      if (!auth.accessToken || !auth.expireIn) throw Object.assign(new Error('钉钉应用认证失败'), { rejected: true });
      token = auth.accessToken;
      expiry = now() + Math.max(0, Number(auth.expireIn) - botConfig.dingTokenRefreshSeconds) * 1000;
    }
    if (!current(bot)) throw Object.assign(new Error('钉钉连接已更新'), { rejected: true });
    const targetFields = group ? { openConversationId: target } : { userIds: [target] };
    const path = group ? '/robot/groupMessages/send' : '/robot/oToMessages/batchSend';
    const result = await jsonRequest<{ code?: string; invalidStaffIdList?: string[]; processQueryKey?: string }>(origin + path, { method: 'POST',
      headers: { 'x-acs-dingtalk-access-token': token }, body: {
        robotCode: secrets.appId, msgKey: 'sampleText',
        msgParam: JSON.stringify({ content: Array.from(text).slice(0, botConfig.dingRobotTextLength).join('') }), ...targetFields,
      } });
    if (result.code || result.invalidStaffIdList?.length) throw Object.assign(new Error('钉钉未接受回复'), { rejected: true });
    return result.processQueryKey ?? null;
  }
  return {
    close: () => client.disconnect(),
    send(target, text, _id, transport) {
      if (transport.channel !== 'dingtalk') throw new Error('钉钉会话类型无效');
      return sendDingBot(target, text, transport.group);
    },
    sendReview: (recipient, text) => sendDingBot(recipient, text, false),
  };
}
