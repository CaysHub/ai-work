import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import type { Express } from 'express';
import { botCredentialKeys } from '../shared/types.ts';
import type { Bot, BotConfiguration, BotSettings, ReplyState, WecomMcpUrls } from '../shared/types.ts';
import type { AgentRun, BotRun, InboundMessage, LiveBot, ReplyReview, ReviewSender, SaveRequest, Response, Secrets, StoredTables, Transport } from './types.ts';
import { requireUser } from './auth.ts';
import { z } from 'zod';
import { all, createChat, enqueue, get, owned, run, seal, transaction, unseal } from './store.ts';
import { httpError, now, parse } from './config.ts';
import { createFeishuMessage, feishuApp, history, jsonRequest, limitWecomText, sendFeishu, sendPersonal, textValue, wecomEndpoint, wecomGroups } from './platform.ts';
import { freshToken, verifyEmployee } from './authorization.ts';
import { skillRows } from './files.ts';
import { skillsField } from './schedules.ts';
import { expireReviews, isReviewBot, notifyReviewer, prepareReply, recordDraft, recoverReviews, reviewWindow } from './replies.ts';
import { publishReplyDocument } from './documents.ts';
import { commandForRun, handleOwnerCommand, notifyCommandResults } from './wecom-commands.ts';
import { beginWecomDelivery, recoverWecomDeliveries, sendWecomParts, wecomReplyParts } from './wecom-delivery.ts';
import { splitWecomReply, wecomPlainText } from './wecom-text.ts';
import { pairWecomFiles, rememberWecomFile } from './wecom-files.ts';
import { generatedReplyFile } from './document-library.ts';

const fields = z.object({
  name: z.string().trim().min(1).max(100), platform: z.enum(['wecom', 'feishu', 'dingtalk']),
  mode: z.enum(['bot', 'personal']), enabled: z.boolean().default(false),
  settings: z.object({
    skills: skillsField, employeeId: z.string().trim().max(200).default(''),
    targets: z.array(z.object({ id: z.string().trim().min(1).max(250), group: z.boolean().default(false) })).max(500).default([]),
    discoverGroups: z.boolean().default(true),
    reviewConversation: z.string().trim().max(250).optional(),
    reviewBotId: z.string().uuid().optional(),
    reviewUserId: z.string().trim().min(1).max(200).optional(),
  }).default({ skills: [], employeeId: '', targets: [], discoverGroups: true }),
  secrets: z.partialRecord(z.enum(botCredentialKeys), z.string().trim().max(4000)).default({}),
});
const hydrate = (bot: StoredTables['bots']): LiveBot => ({ ...bot, settings: parse<BotSettings>(bot.settings), secrets: unseal<Secrets>(bot.secrets) });
function show(bot: StoredTables['bots']): Bot {
  const { secrets, ...visible } = hydrate(bot);
  return { ...visible, configured: botCredentialKeys.filter((key) => secrets[key]) };
}
const current = (bot: LiveBot) => get('SELECT 1 FROM bots WHERE id=? AND version=? AND enabled=1', bot.id, bot.version);
const enabledBots = () => all<StoredTables['bots']>('SELECT * FROM bots WHERE enabled=1').map(hydrate);
function reviewRoute(personal: LiveBot, bots: LiveBot[]) {
  const { reviewBotId, reviewUserId, employeeId } = personal.settings;
  const bot = bots.find((item) => item.mode === 'bot' && item.platform === personal.platform && item.owner === personal.owner &&
    (reviewBotId ? item.id === reviewBotId : personal.platform === 'feishu' && personal.secrets.appId && item.secrets.appId === personal.secrets.appId));
  if (!bot) return;
  const sameFeishuApp = personal.platform === 'feishu' && Boolean(personal.secrets.appId) && personal.secrets.appId === bot.secrets.appId;
  const recipient = sameFeishuApp ? employeeId : reviewUserId ?? (personal.platform === 'wecom' ? employeeId : undefined);
  if (!recipient) return;
  return { bot, recipient, key: JSON.stringify([bot.id, bot.version, recipient]) };
}
const logger = { trace() {}, debug() {}, info() {}, warn() {}, error() {} };
function setStatus(bot: LiveBot, status: Bot['status'], detail: string | null = null) {
  run('UPDATE bots SET status=?,detail=? WHERE id=? AND version=?', status, detail, bot.id, bot.version);
}
function verifyConfigured(bot: z.infer<typeof fields> & { secrets: Secrets }) {
  const { platform, mode, secrets, settings } = bot;
  if (settings.targets.length !== new Set(settings.targets.map((item) => item.id)).size) throw httpError(400, '目标会话 ID 不能重复');
  if (platform === 'wecom') for (const [name, scope] of [['messageMcp', 'msg'], ['documentMcp', 'doc']] as const) {
    const endpoint = secrets[name];
    if (endpoint) {
      try { wecomEndpoint(endpoint, scope); } catch { throw httpError(400, '请输入企业微信官方 ' + scope + ' MCP 授权地址'); }
    }
  }
  if (!bot.enabled) return;
  const required: (keyof Secrets)[] = mode === 'personal' ? [platform === 'wecom' ? 'messageMcp' : 'userToken'] :
    platform === 'wecom' ? ['botId', 'botSecret'] : ['appId', 'appSecret'];
  if (required.some((key) => !secrets[key])) throw httpError(400, '启用连接前请填写所需凭据');
  if (mode === 'personal' && (!settings.employeeId || (!settings.targets.length && !(platform === 'wecom' && settings.discoverGroups)))) {
    throw httpError(400, '本人接管需要本人消息 ID 和接管范围');
  }
}
export function installBots(app: Express) {
  app.get('/api/bots', (req, res) => res.json(all<StoredTables['bots']>('SELECT * FROM bots WHERE owner=? ORDER BY updated DESC', requireUser(req).id).map(show)));
  app.get('/api/bots/:id/configuration', (req, res) => {
    const record = owned('bots', requireUser(req).id, req.params.id);
    const saved = unseal<Secrets>(record.secrets);
    const secrets = Object.fromEntries(botCredentialKeys.map((key) => [key, saved[key]]));
    res.set('Cache-Control', 'no-store').json({ ...show(record), secrets } satisfies BotConfiguration);
  });
  app.get('/api/bots/:id/mcp-urls', (req, res) => {
    const bot = owned('bots', requireUser(req).id, req.params.id);
    if (bot.platform !== 'wecom') throw httpError(404, '此连接没有企微 MCP 地址');
    const { messageMcp, documentMcp } = unseal<Secrets>(bot.secrets);
    res.set('Cache-Control', 'no-store').json({ messageMcp, documentMcp } satisfies WecomMcpUrls);
  });
  function save(req: SaveRequest, res: Response) {
    const owner = requireUser(req).id;
    const old = req.params.id ? hydrate(owned('bots', owner, req.params.id)) : null;
    const input = fields.parse(req.body);
    const id = old?.id ?? randomUUID();
    if (input.mode === 'personal' && old?.platform === input.platform && old.settings.reviewConversation) {
      input.settings.reviewConversation = old.settings.reviewConversation;
    }
    const secrets: Secrets = { ...(old?.platform === input.platform ? old.secrets : {}), ...input.secrets };
    const tokenChanged = Object.hasOwn(input.secrets, 'userToken') && old?.secrets.userToken !== secrets.userToken;
    if (old && (old.settings.employeeId !== input.settings.employeeId || old.secrets.appId !== secrets.appId ||
      old.secrets.appSecret !== secrets.appSecret || tokenChanged)) {
      for (const name of ['refreshToken', 'expiresAt', 'refreshing', 'oauthClientId', 'corpId'] as const) delete secrets[name];
      if (!Object.hasOwn(input.secrets, 'userToken')) delete secrets.userToken;
    }
    skillRows(owner, input.settings.skills);
    verifyConfigured({ ...input, secrets });
    if (old && old.name === input.name && old.platform === input.platform && old.mode === input.mode &&
      Boolean(old.enabled) === input.enabled && isDeepStrictEqual({ discoverGroups: true, ...old.settings }, input.settings) &&
      isDeepStrictEqual(old.secrets, secrets)) {
      res.json(show(owned('bots', owner, id)));
      return;
    }
    transaction(() => {
      const identity = input.platform === 'wecom' ? secrets.botId : secrets.appId;
      if (input.enabled && input.mode === 'bot' && identity &&
        all<StoredTables['bots']>('SELECT * FROM bots WHERE platform=? AND enabled=1 AND mode=\'bot\' AND id<>?', input.platform, id)
          .some((item) => { const keys = unseal<Secrets>(item.secrets); return identity === (keys.botId ?? keys.appId); })) {
        throw httpError(409, '此平台应用已存在启用的连接');
      }
      if (input.mode === 'personal' && input.settings.reviewBotId &&
        (input.enabled || old?.settings.reviewBotId !== input.settings.reviewBotId)) {
        const robot = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND owner=? AND platform=? AND mode=? AND enabled=1',
          input.settings.reviewBotId, owner, input.platform, 'bot');
        if (!robot || robot.id === id) throw httpError(400, '请选择同平台已启用的应用机器人');
        const app = hydrate(robot);
        const sameFeishuApp = input.platform === 'feishu' && Boolean(secrets.appId) && app.secrets.appId === secrets.appId;
        const recipient = sameFeishuApp ? input.settings.employeeId : input.settings.reviewUserId ??
          (input.platform === 'wecom' ? input.settings.employeeId : undefined);
        if (!recipient) throw httpError(400, '请填写机器人侧的本人 ID');
        if (input.enabled && enabledBots().some((item) => item.id !== id && item.mode === 'personal' &&
          reviewRoute(item, [app])?.recipient === recipient)) throw httpError(409, '该机器人私聊已绑定另一条本人接管连接');
      }
      const personalSettings = (settings: BotSettings) => {
        const { reviewBotId: _bot, reviewUserId: _user, ...rest } = settings;
        return { discoverGroups: true, ...rest };
      };
      const sameTakeover = old?.platform === input.platform && old.mode === 'personal' && input.mode === 'personal' &&
        old.name === input.name && Boolean(old.enabled) === input.enabled && isDeepStrictEqual(old.secrets, secrets) &&
        isDeepStrictEqual(personalSettings(old.settings), personalSettings(input.settings));
      run(`INSERT INTO bots VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET name=excluded.name,platform=excluded.platform,mode=excluded.mode,
        settings=excluded.settings,secrets=excluded.secrets,enabled=excluded.enabled,version=excluded.version,
        since=excluded.since,status=excluded.status,detail=NULL,updated=excluded.updated`,
      id, owner, input.name, input.platform, input.mode, JSON.stringify(input.settings), seal(secrets),
      Number(input.enabled), sameTakeover ? old.version : (old?.version ?? 0) + 1, sameTakeover ? old.since : now(),
      sameTakeover ? old.status : input.enabled ? 'connecting' : 'offline', null, now());
    });
    res.json(show(owned('bots', owner, id)));
  }
  app.post('/api/bots', save);
  app.put('/api/bots/:id', save);
  app.delete('/api/bots/:id', (req, res) => {
    run('DELETE FROM bots WHERE owner=? AND id=?', requireUser(req).id, req.params.id);
    res.sendStatus(204);
  });
  app.get('/api/bots/:id/activity', (req, res) => {
    owned('bots', requireUser(req).id, req.params.id);
    res.json(all(`SELECT r.id,r.chat,r.state,r.reply,r.receipt,r.created,c.target FROM runs r JOIN chats c
      ON c.id=r.chat WHERE c.bot=? ORDER BY r.created DESC LIMIT 40`, req.params.id));
  });
}
function accept(bot: LiveBot, target: string | undefined, message: InboundMessage, transport: Transport, title = target) {
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
    const id = enqueue(chat, text.slice(0, 24000), bot.settings.skills, { reply: 'waiting', transport });
    run('INSERT INTO inbox VALUES (?,?,?,?)', bot.id, bot.version, key, id);
    if (bot.platform === 'wecom' && transport.channel === 'personal' && message.sender) {
      pairWecomFiles(bot, target, message.sender, transport.sourceTime, id);
    }
  });
}
export async function mayHandleInbound(row: AgentRun | BotRun) {
  const record = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND enabled=1 AND version=?', row.bot, row.version);
  if (!record || now() - row.created > (isReviewBot(record) ? reviewWindow : 600000)) return false;
  if (record.mode !== 'personal') return true;
  try {
    const bot = hydrate(record);
    if (bot.secrets.refreshing || !row.target || !row.transport) return false;
    const transport = unseal<Transport>(row.transport);
    if (transport.channel !== 'personal') return false;
    const previous = all<{ receipt: string | null; answer: string | null }>(`SELECT receipt,COALESCE(reply_text,answer) AS answer FROM runs WHERE chat=? AND reply='accepted' AND COALESCE(sent_at,created)>=?`,
      row.chat, transport.sourceTime - 1000);
    if (bot.platform === 'wecom') previous.push(...all<{ receipt: string | null; answer: string }>(`
      SELECT p.receipt,p.text AS answer FROM wecom_reply_parts p JOIN runs r ON r.id=p.run_id
      WHERE r.chat=? AND p.state='accepted' AND p.accepted>=?`, row.chat, transport.sourceTime - 1000));
    const latest = await history(bot, row.target, transport.sourceTime, now(), 4);
    const employee = bot.settings.employeeId;
    const manual = latest.some((item) => !item.system && item.sender === employee &&
      !previous.some((old) => old.receipt === item.id || (bot.platform === 'wecom' &&
        old.answer === item.text)));
    return !manual && latest.some((item) => item.id === transport.sourceId) && Boolean(current(bot));
  } catch {
    return false;
  }
}
type ConnectionClient = { close: () => void; send: (target: string, text: string, id: string, transport: Transport) => Promise<string | null>;
  sendReview: (recipient: string, text: string, id: string) => Promise<string | null> };
type Connection = { bot: LiveBot; client?: ConnectionClient; timer?: ReturnType<typeof setTimeout> };
type BotMessage = InboundMessage & { privateChat: boolean; time?: number; replyTo?: string; quotedReview?: string; reply?: ReviewSender };
async function connectBot(bot: LiveBot, receive: (message: BotMessage, target: string | undefined, transport: Transport) => void): Promise<ConnectionClient> {
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
          throw Object.assign(new Error(code === 846607 ? '企微限制机器人主动发送（846607），可在机器人私聊发「接管待办」查看' :
            '企微机器人主动发送失败（' + code + '）'), { rejected: true, rateLimited: code === 846607 });
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
      if (now() - issued > 3600000) { token = await feishuApp(secrets); issued = now(); }
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
    const origin = 'https://api.dingtalk.com/v1.0';
    if (now() >= expiry) {
      const auth = await jsonRequest<{ accessToken?: string; expireIn?: number }>(origin + '/oauth2/accessToken', { method: 'POST',
        body: { appKey: secrets.appId, appSecret: secrets.appSecret } });
      if (!auth.accessToken || !auth.expireIn) throw Object.assign(new Error('钉钉应用认证失败'), { rejected: true });
      token = auth.accessToken;
      expiry = now() + Math.max(0, Number(auth.expireIn) - 120) * 1000;
    }
    if (!current(bot)) throw Object.assign(new Error('钉钉连接已更新'), { rejected: true });
    const targetFields = group ? { openConversationId: target } : { userIds: [target] };
    const path = group ? '/robot/groupMessages/send' : '/robot/oToMessages/batchSend';
    const result = await jsonRequest<{ code?: string; invalidStaffIdList?: string[]; processQueryKey?: string }>(origin + path, { method: 'POST',
      headers: { 'x-acs-dingtalk-access-token': token }, body: {
        robotCode: secrets.appId, msgKey: 'sampleText',
        msgParam: JSON.stringify({ content: Array.from(text).slice(0, 1500).join('') }), ...targetFields,
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
        if (send && time >= Math.max(personal.since - 1000, now() - reviewWindow)) {
          void handleOwnerCommand(personal, { id: message.id, sender: message.sender, text: message.text, time, mentions: [],
            replyTo: message.replyTo }, route.key, send, message.quotedReview, Boolean(message.reply))
            .catch(() => console.warn('接管机器人指令处理失败'));
        }
        return;
      }
    }
    accept(robot, target, message, transport);
  }
  async function poll(bot: LiveBot) {
    bot.secrets = await freshToken(bot);
    await verifyEmployee(bot.platform, bot.secrets, bot.settings.employeeId);
    type Target = BotSettings['targets'][number] & { name?: string | null; first_from?: number };
    const targets = new Map<string, Target>(bot.settings.targets.map((target) => [target.id, target]));
    let failures = 0;
    const end = now() - 2000;
    if (bot.platform === 'wecom' && bot.settings.discoverGroups !== false) {
      const checkpoint = get<{ checkpoint: number }>('SELECT checkpoint FROM group_checkpoints WHERE bot=? AND version=?', bot.id, bot.version)?.checkpoint;
      if (end > bot.since && (!checkpoint || end - checkpoint >= 60000)) {
        const start = Math.max(bot.since, end - 6 * 86400000, (checkpoint ?? end - 15 * 60000) - 15000);
        try {
          const groups = await wecomGroups(bot, start, end);
          if (closed || !current(bot)) return;
          transaction(() => {
            for (const group of groups) run(`INSERT INTO discovered_groups VALUES (?,?,?,?,?,?)
              ON CONFLICT(bot,version,target) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen`,
            bot.id, bot.version, group.id, group.name ?? null, start, end);
            run(`INSERT INTO group_checkpoints VALUES (?,?,?) ON CONFLICT(bot,version)
              DO UPDATE SET checkpoint=excluded.checkpoint`, bot.id, bot.version, end);
          });
        } catch { failures++; }
      }
      for (const group of all<Target>(`SELECT target AS id,name,first_from FROM discovered_groups
        WHERE bot=? AND version=? AND last_seen>=?`, bot.id, bot.version, end - 15 * 60000)) {
        targets.set(group.id, { ...group, group: true });
      }
    }
    async function pollTarget(target: Target) {
      if (closed || !current(bot)) return;
      const checkpoint = get<{ checkpoint: number }>('SELECT checkpoint FROM cursors WHERE bot=? AND version=? AND target=?', bot.id, bot.version, target.id)?.checkpoint;
      const floor = target.first_from ?? bot.since;
      const start = Math.max(floor, (checkpoint ?? floor) - 15000);
      if (start >= end) return;
      if (end - start > 6 * 86400000) throw new Error('接管积压超过六天，请重新启用连接');
      const items = await history(bot, target.id, start, end);
      if (closed || !current(bot)) return;
      if (bot.platform === 'wecom') {
        for (const item of items) {
          if (item.file && !item.system && item.sender !== bot.settings.employeeId) rememberWecomFile(bot, target.id, item);
        }
      }
      for (const item of items) {
        if (item.system || item.sender === bot.settings.employeeId || (target.group && !item.mentions?.includes(bot.settings.employeeId))) continue;
        if (items.some((later) => !later.system && later.sender === bot.settings.employeeId && later.time >= item.time)) continue;
        accept(bot, target.id, item, { channel: 'personal', sourceTime: item.time, sourceId: item.id }, target.name ?? target.id);
      }
      run(`INSERT INTO cursors VALUES (?,?,?,?) ON CONFLICT(bot,version,target) DO UPDATE SET checkpoint=excluded.checkpoint`,
        bot.id, bot.version, target.id, end);
    }
    const list = [...targets.values()];
    for (let index = 0; index < list.length && !closed && current(bot); index += 5) {
      const results = await Promise.allSettled(list.slice(index, index + 5).map(pollTarget));
      failures += results.filter((result) => result.status === 'rejected').length;
    }
    const active = enabledBots();
    const personal = active.find((item) => item.id === bot.id && item.version === bot.version);
    const route = personal && reviewRoute(personal, active);
    setStatus(bot, failures ? 'error' : 'online', failures ? failures + ' 项会话读取或群聊发现失败' :
      !personal || !route ? '请绑定接管机器人以接收待办' : !reviewSender(personal, route) ? '等待接管机器人上线' : null);
  }
  async function deliver(row: BotRun, channel?: { key: string; send: ReviewSender }) {
    const stillPending = () => Boolean(get("SELECT 1 FROM runs WHERE id=? AND reply='pending'", row.id));
    if (!get("SELECT 1 FROM runs WHERE id=? AND reply IN ('pending','awaiting_review')", row.id)) return;
    const record = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND enabled=1 AND version=?', row.bot, row.version);
    const finish = (state: ReplyState, receipt: string | null = null, detail: string | null = null) =>
      run(`UPDATE runs SET reply=?,receipt=?,sent_at=?,error=CASE WHEN state='done' THEN ? ELSE error END
        WHERE id=? AND reply IN ('pending','awaiting_review','sending')`,
        state, receipt, state === 'accepted' ? now() : null, detail, row.id);
    if (!record || now() - row.created > (isReviewBot(record) ? reviewWindow : 600000)) return finish('canceled');
    const bot = hydrate(record);
    const publish = async () => {
      try { return await prepareReply(row.id, () => publishReplyDocument(bot, row.id)); }
      catch (error) {
        const detail = error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]').slice(0, 300) : '平台未返回操作结果';
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
        if (!await checkInbound() || closed || !current(bot)) {
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
      if (!await checkInbound() || closed || !current(bot) || !stillPending()) return finish('canceled');
      let proposed: string[] = [];
      if (!previousParts.length) {
        const link = await publish();
        if (link === undefined || !stillPending()) return;
        if (link && !await checkInbound()) return finish('canceled');
        proposed = splitWecomReply(wecomPlainText(row.answer), link);
        if (!proposed.length) return finish('canceled');
      }
      if (closed || !current(bot)) return finish('canceled');
      if (!beginWecomDelivery(row.id, proposed)) return;
      try {
        finish('accepted', await sendWecomParts(bot, row.target, row.id, () => !closed && Boolean(current(bot)) &&
          Boolean(get("SELECT 1 FROM runs WHERE id=? AND reply='sending'", row.id))), fileDetail);
      }
      catch (error) {
        finish(error instanceof Error && 'canceled' in error ? 'canceled' :
          error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown', null,
          error instanceof Error ? error.message.replace(/https?:\/\/\S+/g, '[地址]').slice(0, 300) : null);
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
    if (closed || !current(bot) || !stillPending()) return finish('canceled');
    const documentLink = await publish();
    if (documentLink === undefined || !stillPending()) return;
    if (documentLink && bot.mode === 'personal' && !await checkInbound()) return finish('canceled');
    if (closed || !current(bot)) return finish('canceled');
    const suffix = documentLink ? '\n\n' + documentLink : '';
    const answer = isReviewBot(bot) ? wecomPlainText(row.answer) : row.answer;
    const body = Array.from(answer).slice(0, 3000 - Array.from(suffix).length).join('');
    const replyText = body + suffix;
    if (!run("UPDATE runs SET reply='sending',reply_text=?,updated=? WHERE id=? AND reply='pending'", replyText, now(), row.id).changes) return;
    try { finish('accepted', await send()); }
    catch (error) { finish(error instanceof Error && 'rejected' in error && error.rejected ? 'failed' : 'unknown'); }
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
            if (bot.mode === 'personal') await poll(bot);
            else {
              connection.client = await connectBot(bot, (message, target, transport) => receive(bot, message, target, transport));
              if (closed || connections.get(bot.id) !== connection) connection.client.close();
            }
          } catch {
            setStatus(bot, 'error', '平台验证或消息拉取失败，请核对权限、凭据和会话 ID');
          } finally {
            if (!closed && connections.get(bot.id) === connection && (bot.mode === 'personal' || !connection.client)) {
              connection.timer = setTimeout(work, 15000);
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
            ORDER BY r.created LIMIT 20`, JSON.stringify([...channels.keys()]));
          for (const row of pending) {
            if (closed) break;
            await deliver(row, channels.get(row.bot));
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
