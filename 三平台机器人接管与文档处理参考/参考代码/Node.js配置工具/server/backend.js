const methods = new Map(Object.entries({
  '/account/center/getUserInfo': 'GET',
  '/admin/im/bot/page': 'POST',
  '/admin/im/bot/detail': 'GET',
  '/admin/im/bot/create': 'POST',
  '/admin/im/bot/update': 'POST',
  '/admin/im/bot/enable': 'POST',
  '/admin/im/bot/disable': 'POST',
  '/admin/im/bot/user/page': 'POST',
  '/admin/im/bot/user/grant': 'POST',
  '/admin/im/bot/session/page': 'POST',
  '/admin/im/bot/takeover/profile/detail': 'GET',
  '/admin/im/bot/takeover/profile': 'POST',
  '/admin/im/bot/takeover/update': 'POST',
  '/im/bot/page': 'POST',
  '/im/bot/application/page': 'POST',
  '/im/bot/application/detail': 'GET',
  '/im/bot/application/update': 'POST',
  '/im/bot/binding/create-code': 'POST',
  '/im/bot/takeover/detail': 'GET',
  '/im/bot/takeover/oauth/start': 'POST',
  '/im/bot/takeover/oauth/complete': 'POST',
}));

export class ConfigError extends Error {
  constructor(message, code = 'CONFIG_ERROR') {
    super(message);
    this.code = code;
  }
}

export function text(value, field, max = 256) {
  if (typeof value !== 'string' || !value.trim() || value.length > max || /[\x00-\x1f\x7f]/.test(value)) {
    throw new ConfigError(field + '格式不正确');
  }
  return value.trim();
}

export function baseUrl(target, prefix) {
  const input = text(target, '目标地址', 2048);
  const path = text(prefix || '/servyou-work-backend', '后端路径').replace(/\/+$/, '');
  if (!/^\/(?:[a-zA-Z0-9_-]+\/?)+$/.test(path)) throw new ConfigError('后端路径格式不正确');
  let url;
  try { url = new URL(input.includes('://') ? input : 'https://' + input); }
  catch { throw new ConfigError('目标地址格式不正确'); }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) {
    throw new ConfigError('目标地址应为 HTTP 或 HTTPS 地址');
  }
  return url.origin + path;
}

async function readJson(response) {
  if (!response.body) throw new ConfigError('后端响应为空', 'INVALID_RESPONSE');
  const reader = response.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > 2 * 1024 * 1024) throw new ConfigError('后端返回的数据过大', 'INVALID_RESPONSE');
      chunks.push(value);
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
  const raw = Buffer.concat(chunks).toString('utf8').replace(/^\uFEFF/, '');
  try {
    const result = JSON.parse(raw);
    if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error();
    return result;
  } catch {
    throw new ConfigError('后端返回非预期数据。请核对服务地址和登录状态（HTTP ' + response.status + '）', 'INVALID_RESPONSE');
  }
}

export class Backend {
  constructor(request, steps = []) {
    this.base = baseUrl(request.target, request.prefix);
    if (!request.cookie) throw new ConfigError('请复制工作台 Cookie 到当前标签页，然后刷新', 'COOKIE_REQUIRED');
    const cookie = text(request.cookie, '浏览器 Cookie', 20_000);
    if (/[^\x20-\x7e]/.test(cookie)) throw new ConfigError('浏览器 Cookie 格式不正确');
    this.cookie = cookie;
    this.tenant = text(request.tenantId, '管理租户', 128);
    this.steps = steps;
  }

  async call(path, body, params) {
    const method = methods.get(path);
    if (!method) throw new ConfigError('接口未开放');
    const url = new URL(this.base + path);
    for (const [key, value] of Object.entries(params || {})) url.searchParams.set(key, value);
    const headers = { Cookie: this.cookie, Accept: 'application/json', 'Content-Type': 'application/json' };
    if (path.startsWith('/admin/')) headers['X-Admin-Tenant-Id'] = this.tenant;
    const slow = path.includes('/oauth/') || path.includes('/binding/');
    let response;
    let result;
    try {
      response = await fetch(url, {
        method, headers, redirect: 'manual',
        body: method === 'POST' ? JSON.stringify(body || {}) : undefined,
        signal: AbortSignal.timeout(slow ? 120_000 : 45_000),
      });
      result = await readJson(response);
    } catch (error) {
      this.steps.push({ method, path, httpStatus: response?.status, code: error.code || 'REQUEST_UNCONFIRMED', success: false });
      if (error instanceof ConfigError && method !== 'POST') throw error;
      throw new ConfigError(method === 'POST'
        ? '接口结果未确认，请刷新状态核对后再操作。'
        : '连接失败，请核对地址、网络和证书。', error.code || 'REQUEST_UNCONFIRMED');
    }
    const code = String(result.code ?? ('HTTP_' + response.status));
    this.steps.push({ method, path, httpStatus: response.status, code, success: response.ok && result.success === true });
    if (response.ok && result.success === true) return result;
    const hint = ['UNAUTHORIZED', 'SESSION_NOTFOUND', 'SESSION_OBJECT_MISSING', 'SESSION_TIME_OUT'].includes(code) || response.status === 401
      ? '登录已失效，请更新对应账号的 Cookie。' : '';
    throw new ConfigError(hint + String(result.message || '接口请求失败'), code);
  }

  async pages(path, body) {
    const rows = [];
    for (let page = 1; page <= 100; page++) {
      const result = await this.call(path, { ...body, pageNum: page, pageSize: 100 });
      if (!Array.isArray(result.data)) throw new ConfigError('分页响应格式不正确', 'INVALID_RESPONSE');
      rows.push(...result.data);
      if (page >= Number(result.totalPage ?? page) || result.data.length < 100) return rows;
    }
    throw new ConfigError('数据超过查询上限，请缩小管理租户范围');
  }

  async find(preset) {
    const found = (await this.pages('/admin/im/bot/page', { platform: preset.platform }))
      .filter(bot => bot.externalAppId === preset.appId);
    if (found.length > 1) throw new ConfigError('存在多个同应用配置，请在工作台核对');
    return found.length ? this.detail(found[0].botKey) : null;
  }

  async detail(botKey, includeMcpUrls = false) {
    const params = includeMcpUrls ? { botKey, includeMcpUrls } : { botKey };
    const { data } = await this.call('/admin/im/bot/detail', undefined, params);
    if (!data || !/^\d+$/.test(String(data.botKey)) || !/^\d+$/.test(String(data.configVersion))) {
      throw new ConfigError('后端配置标识格式不正确', 'INVALID_RESPONSE');
    }
    return { ...data, botKey: String(data.botKey), configVersion: String(data.configVersion) };
  }

  async profile(botKey, ownerUserId, create = false) {
    const path = '/admin/im/bot/takeover/profile';
    const payload = { botKey, ownerUserId };
    const result = create ? await this.call(path, payload) : await this.call(path + '/detail', undefined, payload);
    return result.data ?? null;
  }

  async toggle(bot, enable) {
    await this.call('/admin/im/bot/' + (enable ? 'enable' : 'disable'), {
      botKey: bot.botKey, expectedConfigVersion: bot.configVersion, reason: '独立配置工具',
    });
    return this.detail(bot.botKey);
  }

  async account() {
    const { data } = await this.call('/account/center/getUserInfo');
    const userId = text(String(data?.userId ?? ''), '登录账号 ID', 64);
    return { userId, userName: data.userName || userId };
  }

  async requireSelf(owner) {
    const actual = await this.account();
    if (actual.userId !== owner) throw new ConfigError('接管人是 ' + owner + '，当前浏览器账号为 ' + actual.userId);
  }
}
