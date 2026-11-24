import type { Express } from 'express';
import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { z } from 'zod';
import { botLimits } from '../../shared/config.ts';
import type { BotConfiguration, BotSettings, WecomMcpUrls } from '../../shared/types.ts';
import { botCredentialKeys } from '../../shared/types.ts';
import { requireUser } from '../account/auth.ts';
import { skillsField } from '../chat/fields.ts';
import { httpError, now } from '../common/runtime.ts';
import type { Response, SaveRequest, Secrets, StoredTables } from '../common/types.ts';
import { wecomEndpoint } from '../platform/mcp.ts';
import { skillRows } from '../skills/skills.ts';
import { all, get, owned, run, seal, transaction, unseal } from '../storage/store.ts';
import { botConfig } from './bots.config.ts';

import { enabledBots, hydrate, reviewRoute, show } from './records.ts';

const fields = z.object({
  name: z.string().trim().min(1).max(botLimits.name), platform: z.enum(['wecom', 'feishu', 'dingtalk']),
  mode: z.enum(['bot', 'personal']), enabled: z.boolean().default(botConfig.enabled),
  settings: z.object({
    skills: skillsField, employeeId: z.string().trim().max(botConfig.employeeIdLength).default(''),
    targets: z.array(z.object({ id: z.string().trim().min(1).max(botConfig.targetIdLength), group: z.boolean().default(false) })).max(botConfig.targetLimit).default([]),
    groupMentionName: z.string().trim().min(1).max(botLimits.groupMentionName)
      .refine((name) => !/[@＠\r\n]/.test(name) && !/^(?:所有人|全体成员|全体|all|everyone)$/i.test(name), '群内 @ 名称请填写本人的显示名称').optional(),
    discoverGroups: z.boolean().default(true),
    reviewConversation: z.string().trim().max(botConfig.targetIdLength).optional(),
    reviewBotId: z.string().uuid().optional(),
    reviewUserId: z.string().trim().min(1).max(botConfig.employeeIdLength).optional(),
  }).default({ skills: [], employeeId: '', targets: [], discoverGroups: true }),
  secrets: z.partialRecord(z.enum(botCredentialKeys), z.string().trim().max(botConfig.credentialLength)).default({}),
});
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
    if (input.mode !== 'personal') delete input.settings.groupMentionName;
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
      ON c.id=r.chat WHERE c.bot=? ORDER BY r.created DESC LIMIT ?`, req.params.id, botConfig.activityLimit));
  });
}
