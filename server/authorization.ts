import { randomBytes } from 'node:crypto';
import type { Express } from 'express';
import type { BotSettings, Platform } from '../shared/types.ts';
import type { LiveBot, Secrets, StoredTables } from './types.ts';
import { requireUser } from './auth.ts';
import { config, httpError, now, parse } from './config.ts';
import { get, owned, run, seal, transaction, unseal } from './store.ts';
import { dingCall, jsonRequest, lark } from './platform.ts';

type AuthRow = { state: string; bot: string; version: number; payload: string };
type FeishuToken = { code?: number; access_token?: string; refresh_token?: string; expires_in?: number };
type DingToken = { accessToken?: string; refreshToken?: string; corpId?: string; expiresIn?: number };
type DeviceGrant = { verificationUriComplete: string; deviceCode: string; flowId?: string; expiresIn: number };
type DeviceResult = { status?: string; error?: string; authCode?: string };
type Envelope<T> = { success?: boolean; result?: T; data?: T };
type DevicePayload = Pick<DeviceGrant, 'deviceCode' | 'flowId'> & { clientId: string };

const larkAuth = 'https://accounts.feishu.cn';
const dingAuth = 'https://mcp.dingtalk.com';
const dingLogin = 'https://login.dingtalk.com';
const dingDeviceGrant = 'urn:ietf:params:oauth:grant-type:device_code';
const scopes = 'im:message im:message:readonly im:message.p2p_msg:get_as_user im:message.group_msg:get_as_user im:message.send_as_user offline_access';
const callbackPath = '/integrations/feishu/callback';
const tokenDuration = (seconds: unknown) => {
  const expiry = Number(seconds);
  if (!Number.isFinite(expiry) || expiry <= 0 || expiry > 365 * 86400) throw new Error('平台未返回有效的令牌期限');
  return now() + expiry * 1000;
};
function value<T>(response: Envelope<T> & Partial<T>): T {
  if (response.success === false && !(response.data && typeof response.data === 'object' && 'status' in response.data)) throw new Error('平台授权请求失败');
  return response.data ?? response.result ?? response as T;
}
async function feishuToken(secrets: Secrets, fields: Record<string, string | undefined>) {
  const data = await jsonRequest<FeishuToken>(larkAuth + '/oauth/v3/token', {
    method: 'POST', body: { client_id: secrets.appId, client_secret: secrets.appSecret, ...fields },
  });
  if (!data.access_token || !data.refresh_token || (data.code != null && data.code !== 0)) throw new Error('飞书未返回有效的本人令牌');
  return { userToken: data.access_token, refreshToken: data.refresh_token, expiresAt: tokenDuration(data.expires_in) };
}
async function dingtalkToken(action: string, clientId: string | undefined, fields: Record<string, string | undefined>) {
  const data = value<DingToken>(await jsonRequest(dingAuth + '/oauth2/' + action, { method: 'POST', body: { clientId, ...fields } }));
  if (!data.accessToken || !data.refreshToken || !data.corpId) throw new Error('钉钉未返回有效的组织用户令牌');
  return { userToken: data.accessToken, refreshToken: data.refreshToken, corpId: data.corpId,
    expiresAt: tokenDuration(data.expiresIn), oauthClientId: clientId };
}
export async function verifyEmployee(platform: Platform, secrets: Secrets, employeeId: string) {
  if (platform === 'feishu') {
    const data = await lark<{ open_id?: string }>('/authen/v1/user_info', { token: secrets.userToken });
    if (data.open_id !== employeeId) throw new Error('飞书授权人与配置的本人 ID 不一致');
  }
  if (platform === 'dingtalk') {
    const profiles = await dingCall<{ orgEmployeeModel?: { openDingTalkId?: string; corpId?: string } }[]>(secrets.userToken, 'get_current_user_profile', {});
    if (!Array.isArray(profiles) || !profiles.some(({ orgEmployeeModel: profile }) =>
      profile?.openDingTalkId === employeeId && (!secrets.corpId || profile.corpId === secrets.corpId))) {
      throw new Error('钉钉授权人与配置的本人 ID 不一致');
    }
  }
}
export async function freshToken(bot: LiveBot) {
  const row = get<{ secrets: string }>('SELECT secrets FROM bots WHERE id=? AND version=? AND enabled=1', bot.id, bot.version);
  if (!row) throw new Error('授权配置已变更');
  const secrets = unseal<Secrets>(row.secrets);
  if (secrets.refreshing) throw new Error('本人令牌续期结果待核对，请重新授权');
  if (!secrets.expiresAt || secrets.expiresAt - now() > 120000) return secrets;
  const flag = seal({ ...secrets, refreshing: true });
  if (!run('UPDATE bots SET secrets=? WHERE id=? AND version=? AND secrets=?', flag, bot.id, bot.version, row.secrets).changes) {
    throw new Error('本人授权正在更新');
  }
  const updated = bot.platform === 'feishu' ? await feishuToken(secrets, {
    grant_type: 'refresh_token', refresh_token: secrets.refreshToken,
  }) : await dingtalkToken('refreshToken', secrets.oauthClientId, {
    grantType: 'refresh_token', refreshToken: secrets.refreshToken,
  });
  if (secrets.corpId && (!('corpId' in updated) || updated.corpId !== secrets.corpId)) throw new Error('钉钉授权的组织发生变化');
  await verifyEmployee(bot.platform, updated, bot.settings.employeeId);
  const result = { ...secrets, ...updated };
  if (!run('UPDATE bots SET secrets=? WHERE id=? AND version=? AND secrets=?', seal(result), bot.id, bot.version, flag).changes) {
    throw new Error('本人授权配置已变更');
  }
  return result;
}

function remember(bot: StoredTables['bots'], state: string, payload: DevicePayload | { redirectUri: string }, expires: number) {
  transaction(() => {
    if (!get('SELECT 1 FROM bots WHERE id=? AND version=?', bot.id, bot.version)) throw httpError(409, '连接配置已变更，请重新授权');
    run('DELETE FROM bot_authorizations WHERE expires<=? OR bot=?', now(), bot.id);
    run('INSERT INTO bot_authorizations VALUES (?,?,?,?,?)', state, bot.id, bot.version, expires, seal(payload));
  });
}
function botFor(row: AuthRow): LiveBot {
  const bot = get<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND version=? AND mode=\'personal\'', row.bot, row.version);
  if (!bot) throw httpError(409, '授权对应的连接配置已变更');
  return { ...bot, settings: parse<BotSettings>(bot.settings), secrets: unseal(bot.secrets) };
}
function authRecord(state: string) {
  const row = get<AuthRow>('SELECT * FROM bot_authorizations WHERE state=? AND expires>?', state, now());
  if (!row) throw httpError(400, '授权已失效，请重新开始');
  return row;
}
function claim(row: AuthRow) {
  if (!run('DELETE FROM bot_authorizations WHERE state=? AND expires>?', row.state, now()).changes) {
    throw httpError(409, '授权已处理或过期');
  }
}
async function storeAuthorization(bot: LiveBot, token: Secrets) {
  await verifyEmployee(bot.platform, token, bot.settings.employeeId);
  const updated = seal({ ...bot.secrets, ...token, refreshing: false });
  if (!run('UPDATE bots SET secrets=?,version=version+1,since=?,updated=?,detail=NULL WHERE id=? AND version=?',
    updated, now(), now(), bot.id, bot.version).changes) throw httpError(409, '连接配置已变更，请重新授权');
}
export function installFeishuCallback(app: Express) {
  app.get(callbackPath, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    try {
      if (typeof req.query.state !== 'string' || typeof req.query.code !== 'string') throw new Error();
      const row = authRecord(req.query.state);
      const bot = botFor(row);
      if (bot.platform !== 'feishu') throw new Error();
      claim(row);
      const token = await feishuToken(bot.secrets, {
        grant_type: 'authorization_code', code: req.query.code, redirect_uri: unseal<{ redirectUri: string }>(row.payload).redirectUri,
      });
      await storeAuthorization(bot, token);
      res.type('text').send('飞书授权成功。请返回工作台刷新连接并启用本人接管。');
    } catch {
      res.status(400).type('text').send('飞书授权失败。请核对应用权限、回调地址和本人消息 ID，再重新授权。');
    }
  });
}
export function installAuthorizations(app: Express) {
  app.post('/api/bots/:id/authorize', async (req, res) => {
    const bot = owned('bots', requireUser(req).id, req.params.id);
    const settings = parse<BotSettings>(bot.settings);
    if (bot.mode !== 'personal' || !settings.employeeId) throw httpError(400, '请先配置本人接管及本人消息 ID');
    const state = randomBytes(24).toString('hex');
    try {
      if (bot.platform === 'feishu') {
        const secrets = unseal<Secrets>(bot.secrets);
        if (!config.publicBaseUrl || !secrets.appId || !secrets.appSecret) {
          throw httpError(400, '请先配置飞书应用凭据和工作台 publicBaseUrl');
        }
        const redirectUri = new URL(callbackPath, config.publicBaseUrl).href;
        const url = new URL('/open-apis/authen/v1/authorize', larkAuth);
        url.search = new URLSearchParams({ client_id: secrets.appId, redirect_uri: redirectUri, state, scope: scopes }).toString();
        remember(bot, state, { redirectUri }, now() + 300000);
        return res.json({ url: url.href });
      }
      if (bot.platform !== 'dingtalk') throw httpError(400, '请填写企业微信消息 MCP 授权地址');
      const clientId = value<string>(await jsonRequest(dingAuth + '/cli/clientId'));
      if (typeof clientId !== 'string') throw new Error();
      const grant = value<DeviceGrant>(await jsonRequest(dingLogin + '/oauth2/device/code.json', {
        method: 'POST', form: { client_id: clientId, scope: 'openid corpid' },
      }));
      const url = new URL(grant.verificationUriComplete);
      if (url.origin !== dingLogin || url.username || url.password || !grant.deviceCode) throw new Error();
      remember(bot, state, { clientId, deviceCode: grant.deviceCode, flowId: grant.flowId }, Math.min(tokenDuration(grant.expiresIn), now() + 900000));
      return res.json({ url: url.href, state });
    } catch (error) {
      if (error instanceof Error && 'status' in error) throw error;
      throw httpError(502, '无法开始平台授权，请检查应用配置');
    }
  });
  app.post('/api/bots/:id/authorize/finish', async (req, res) => {
    const ownedBot = owned('bots', requireUser(req).id, req.params.id);
    if (typeof req.body?.state !== 'string') throw httpError(400, '缺少授权状态');
    const row = authRecord(req.body.state);
    if (row.bot !== ownedBot.id || ownedBot.platform !== 'dingtalk') throw httpError(400, '当前连接与授权请求不匹配');
    const bot = botFor(row);
    const { clientId, flowId, deviceCode } = unseal<DevicePayload>(row.payload);
    try {
      const result = flowId ? value<DeviceResult>(await jsonRequest(dingAuth + '/cli/oauth/device/poll?' + new URLSearchParams({ flowId }))) :
        value<DeviceResult>(await jsonRequest(dingLogin + '/oauth2/device/token.json', { method: 'POST', form: {
          grant_type: dingDeviceGrant, device_code: deviceCode, client_id: clientId,
        } }));
      if (['REJECTED', 'EXPIRED'].includes(result.status ?? '') || ['access_denied', 'expired_token'].includes(result.error ?? '')) {
        claim(row);
        throw httpError(400, '钉钉授权已拒绝或过期');
      }
      if (!result.authCode) throw httpError(409, '请先确认钉钉授权，再完成绑定');
      claim(row);
      const token = await dingtalkToken('getToken', clientId, { authCode: result.authCode, grantType: 'authorization_code' });
      const permission = value<{ cliAuthEnabled?: boolean }>(await jsonRequest(dingAuth + '/cli/cliAuthEnabled', { headers: { 'x-user-access-token': token.userToken } }));
      if (permission.cliAuthEnabled !== true) throw httpError(403, '企业尚未向此员工开放钉钉 Workspace 渠道');
      await storeAuthorization(bot, token);
      res.json({ authorized: true });
    } catch (error) {
      if (error instanceof Error && 'status' in error) throw error;
      throw httpError(502, '钉钉授权未完成，请核对组织、渠道权限和本人消息 ID');
    }
  });
}
