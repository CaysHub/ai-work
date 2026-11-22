import { ConfigError, text } from './backend.js';
import { capabilities, presets, preloaded } from './presets.js';

const root = '/admin/im/bot/';
const wecomIdentityConflict = 'IM_BOT_WECOM_IDENTITY_CONFLICT';
const lookupPreset = id => {
  const preset = presets.find(entry => entry.id === id);
  if (!preset) throw new ConfigError('请选择机器人应用');
  return preset;
};

function ids(values) {
  const list = typeof values === 'string' ? values.split(/[,，\n]/).map(v => v.trim()).filter(Boolean) : values ?? [];
  if (!Array.isArray(list) || list.length > 100) throw new ConfigError('每组会话最多可填写 100 个 ID');
  return [...new Set(list.map(value => text(value, '会话 ID')))];
}

function activeWecomIdentity(bot) {
  if (bot?.activeBotKey) return {
    botKey: bot.activeBotKey, configVersion: bot.activeBotConfigVersion,
    identityMode: bot.activeIdentityMode, ownerUserId: bot.activeOwnerUserId,
  };
  if (bot?.enabled) return { botKey: bot.botKey, configVersion: bot.configVersion, identityMode: bot.identityMode || 'BOT' };
  return null;
}

function requireVacantWecomIdentity(bot, targetKey, identityMode) {
  const active = activeWecomIdentity(bot);
  if (active && (active.botKey !== targetKey || active.identityMode !== identityMode)) {
    throw new ConfigError('同一企微 BotID 已有启用配置，请先停用当前身份再切换', wecomIdentityConflict);
  }
}

async function loadBot(client, preset, bot, owner) {
  const result = { presetId: preset.id, bot: null, takeover: null, users: [], groups: [] };
  if (!bot) return result;
  result.users = await client.pages(root + 'user/page', { botKey: bot.botKey });
  result.groups = await client.pages(root + 'session/page', { botKey: bot.botKey, sessionType: 'GROUP' });
  const profile = await client.profile(bot.botKey, owner);
  result.takeover = profile;
  if (preset.platform === 'WECOM') {
    result.rootBot = bot;
    result.activeIdentity = activeWecomIdentity(bot);
  }
  const profileBot = profile && (String(profile.botKey) === String(bot.botKey)
    ? bot : await client.detail(profile.botKey, preset.platform === 'WECOM'));
  result.bot = profileBot || (bot.identityMode === 'USER'
    ? { ...bot, mcpUrls: undefined, enabled: false, connectionState: 'DISABLED', identityMode: 'BOT' } : bot);
  return result;
}

export async function inspect(input, client) {
  const user = await client.account();
  const ownerUserId = input.ownerUserId ? text(input.ownerUserId, '接管人账号', 64) : user.userId;
  let applications = [];
  let applicationAvailable = true;
  try {
    const summaries = await client.pages('/im/bot/application/page', {});
    applications = await Promise.all(summaries.map(async summary =>
      (await client.call('/im/bot/application/detail', undefined, { botKey: summary.botKey })).data));
  } catch (error) {
    if (client.steps.at(-1)?.httpStatus !== 404 && error.code !== 'NOT_FOUND') throw error;
    applicationAvailable = false;
  }
  const applicationByKey = new Map(applications.map(application => [application.botKey, application]));
  const memberRecord = (bot, application = null) => ({
      presetId: 'member-' + bot.botKey, bot, application, applicationAvailable, takeover: null, groups: [], member: true,
      users: [{ userId: user.userId, userName: user.userName, authorized: bot.authorized, bound: bot.bound }],
  });
  let rows;
  try {
    rows = await client.pages(root + 'page', {});
  } catch (error) {
    if (error.code !== 'NO_PERMISSION') throw error;
    const personal = applications.length ? applications : await client.pages('/im/bot/page', {});
    const bots = personal.filter(bot => presets.some(preset => preset.platform === bot.platform))
      .map(bot => memberRecord(bot, applicationByKey.get(bot.botKey)));
    return { user, ownerUserId: user.userId, bots, member: true };
  }
  const bots = [];
  for (const preset of presets) {
    const found = rows.find(row => row.platform === preset.platform && row.externalAppId === preset.appId);
    const bot = found ? await client.detail(found.botKey, preset.platform === 'WECOM') : null;
    bots.push({
      ...await loadBot(client, preset, bot, ownerUserId),
      application: applicationByKey.get(found?.botKey),
      applicationAvailable,
      existingWecomMessageConfig: preset.platform === 'WECOM' && bot?.identityMode === 'USER'
        && bot.configuredCapabilities?.includes('msg') === true,
    });
  }
  for (const application of applications) {
    if (!presets.some(preset => preset.platform === application.platform && preset.appId === application.externalAppId))
      bots.push(memberRecord(application, application));
  }
  return { user, ownerUserId, apiBase: client.base, bots };
}

function optionsFor(input) {
  const options = { ...input.options };
  options.identityMode ??= 'BOT';
  if (!['BOT', 'USER'].includes(options.identityMode)) {
    throw new ConfigError('请选择该平台支持的使用身份');
  }
  options.ownerUserId = text(options.ownerUserId, '工作台账号 ID', 64);
  return options;
}

function savedCredentials(preset, options, current) {
  const defaults = preloaded[preset.id] ?? {};
  const secret = options.secret || (!current ? defaults.secret : '');
  if (!current && !secret) throw new ConfigError('新建机器人需要填写应用密钥');
  const urls = {};
  if (preset.platform === 'WECOM') {
    for (const [key, value] of Object.entries(options.mcpUrls ?? {})) {
      if (!value || value === current?.mcpUrls?.[key]) continue;
      if (!Object.hasOwn(capabilities, key)) throw new ConfigError('请选择有效的企微能力');
      let url;
      try { url = new URL(text(value, '企微授权地址', 4096)); }
      catch { throw new ConfigError('企微授权地址格式不正确'); }
      if (url.origin !== 'https://qyapi.weixin.qq.com' || url.pathname !== '/mcp/v2/bot/' + key ||
          url.username || url.password || url.hash || !url.searchParams.get('apikey')) throw new ConfigError('请填写对应能力的企微官方授权地址');
      urls[key] = url.href;
    }
  }
  return { ...(secret ? { secret: text(secret, '应用密钥', 4096) } : {}), ...(Object.keys(urls).length ? { mcpUrls: urls } : {}) };
}

async function ensureGrant(client, botKey, ownerUserId) {
  const owners = await client.pages(root + 'user/page', { botKey });
  if (!owners.some(user => user.userId === ownerUserId && user.authorized)) {
    await client.call(root + 'user/grant', { botKey, targetUserId: ownerUserId, reason: '独立配置页面授权' });
  }
}

export async function apply(input, client) {
  const preset = lookupPreset(input.presetId);
  const options = optionsFor(input);
  const owner = options.ownerUserId;
  const original = await client.find(preset);
  const takeoverMode = options.identityMode === 'USER';
  let current = original;
  let takeoverFields;
  if (takeoverMode) {
    if (!original) throw new ConfigError('请先创建普通机器人并完成账号绑定');
    if (options.secret) throw new ConfigError('应用密钥请在普通机器人配置中维护');
    const users = await client.pages(root + 'user/page', { botKey: original.botKey });
    if (!users.some(user => user.userId === owner && user.authorized && user.bound)) throw new ConfigError('请点击「绑定账号」完成授权和绑定');
    const profile = await client.profile(original.botKey, owner);
    if (input.enableAfter && preset.platform === 'WECOM') {
      requireVacantWecomIdentity(original, profile?.botKey, options.identityMode);
    }
    if (profile && input.configVersion && String(profile.configVersion) !== input.configVersion) {
      throw new ConfigError('配置已变化，请先刷新状态', 'VERSION_CONFLICT');
    }
    let messageUserId;
    if (preset.platform === 'WECOM') {
      const entered = typeof options.messageUserId === 'string' ? options.messageUserId.trim() : options.messageUserId;
      const value = entered || profile?.messageUserId;
      if (!value) throw new ConfigError('请填写接管本人在企微消息 MCP 中的 userid');
      messageUserId = text(value, '企微消息 MCP 本人 userid', 256);
    }
    takeoverFields = {
      messageUserId, privateChatIds: ids(options.privateChatIds), groupChatIds: ids(options.groupChatIds),
      groupMentionName: typeof options.groupMentionName === 'string' ? options.groupMentionName.trim() : undefined,
      discoverEnabled: options.discoverEnabled === true,
    };
    if (input.enableAfter && !options.autoProcessEnabled) throw new ConfigError('请开启自动处理并回复');
    const target = profile ?? await client.profile(original.botKey, owner, true);
    if (!target?.botKey) throw new ConfigError('未取得接管配置', 'INVALID_RESPONSE');
    if (preset.platform === 'WECOM' && !Object.hasOwn(target, 'messageUserId')) {
      throw new ConfigError('目标服务需要支持企微双标识');
    }
    if (!target.externalUserId) throw new ConfigError('未取得接管人的平台绑定 ID，请刷新后核对绑定记录');
    takeoverFields.externalUserId = text(target.externalUserId, '平台绑定 ID', 256);
    current = await client.detail(target.botKey, preset.platform === 'WECOM');
  } else if (current?.identityMode === 'USER' && preset.platform !== 'WECOM') {
    throw new ConfigError('原配置使用真人身份，请选择对应人员');
  } else if (current && input.configVersion && current.configVersion !== input.configVersion) {
    throw new ConfigError('配置已变化，请先刷新状态', 'VERSION_CONFLICT');
  } else if (current && preset.platform === 'WECOM') {
    current = await client.detail(current.botKey, true);
    if (input.configVersion && current.configVersion !== input.configVersion) {
      throw new ConfigError('配置已变化，请先刷新状态', 'VERSION_CONFLICT');
    }
    if (input.enableAfter || current.enabled && current.identityMode === 'USER') {
      requireVacantWecomIdentity(current, current.botKey, options.identityMode);
    }
    if (current.identityMode === 'USER') {
      const { data: switched } = await client.call(root + 'takeover/update', {
        botKey: current.botKey, expectedConfigVersion: current.configVersion, identityMode: 'BOT',
      });
      current = await client.detail(switched.botKey, true);
    }
  }
  const body = {
    platform: preset.platform, externalAppId: preset.appId,
    name: text(options.name, '显示名称', 128), description: current?.description,
    autoProcessEnabled: options.autoProcessEnabled === true, groupEnabled: options.groupEnabled === true,
    allowedGroupChatIds: takeoverMode ? (current?.allowedGroupChatIds ?? []) : ids(options.allowedGroupChatIds),
    ...savedCredentials(preset, options, current),
  };
  if (current?.enabled) current = await client.toggle(current, false);
  if (current) Object.assign(body, { botKey: current.botKey, expectedConfigVersion: current.configVersion });
  const { data } = await client.call(root + (current ? 'update' : 'create'), body);
  current = await client.detail(data.botKey);
  if (options.grantOwner && !takeoverMode) await ensureGrant(client, current.botKey, owner);
  if (takeoverMode) {
    const { data: profile } = await client.call(root + 'takeover/update', {
      botKey: current.botKey, expectedConfigVersion: current.configVersion,
      ownerUserId: owner, identityMode: 'USER', ...takeoverFields,
    });
    if (preset.platform === 'WECOM' && profile?.messageUserId !== takeoverFields.messageUserId) {
      throw new ConfigError('服务未确认新的企微身份 ID，配置保持停用');
    }
    current = await client.detail(current.botKey);
    if (!input.enableAfter && preset.platform === 'WECOM' && profile?.authorizationReady !== true) {
      return { message: '接管配置已保存，启用需完成企微消息授权' };
    }
    if (input.enableAfter && profile?.authorizationReady !== true) {
      return { message: preset.platform === 'WECOM' ? '配置已保存，请填写企微消息 MCP 授权' : '配置已保存，请完成本人授权' };
    }
  }
  if (input.enableAfter) await client.toggle(current, true);
  return { message: input.enableAfter ? '配置已保存并启用' : '配置已保存，机器人已停用' };
}

function normalizeOAuth(data, platform) {
  const url = new URL(text(data?.authorizationUrl, '授权链接', 16_000));
  if (platform === 'DINGTALK') {
    if (url.origin !== 'https://login.dingtalk.com' || url.pathname !== '/oauth2/device/verify.htm' ||
        url.username || url.password || !url.searchParams.get('user_code')) throw new ConfigError('钉钉授权链接格式不正确');
    return { authorizationUrl: url.href, configVersion: String(data.configVersion), botKey: String(data.botKey) };
  }
  if (url.origin !== 'https://accounts.feishu.cn' || url.pathname !== '/open-apis/authen/v1/authorize' ||
      url.username || url.password) throw new ConfigError('后端返回的飞书授权链接不符合预期');
  const scopes = (url.searchParams.get('scope') || '').split(/\s+/).filter(Boolean)
    .map(value => value === 'im:message:send_as_user' ? 'im:message.send_as_user' : value);
  if (scopes.includes('im:message.send_as_user')) scopes.push('im:message');
  if (scopes.length) url.searchParams.set('scope', [...new Set(scopes)].join(' '));
  return { authorizationUrl: url.href, configVersion: String(data.configVersion), botKey: String(data.botKey) };
}

async function authorize(input, client, profile, start, platform) {
  const payload = { botKey: profile.botKey, expectedConfigVersion: String(profile.configVersion) };
  const dingTalk = platform === 'DINGTALK';
  if (start) {
    if (dingTalk) {
      const { data } = await client.call('/im/bot/takeover/oauth/start', payload);
      return { message: '已生成钉钉授权链接', oauth: normalizeOAuth(data, platform) };
    }
    let redirect;
    try { redirect = new URL(text(input.redirectUri, '回调地址', 2048)); }
    catch { throw new ConfigError('请填写已登记的 HTTPS 回调地址'); }
    if (redirect.protocol !== 'https:' || redirect.username || redirect.password || redirect.hash) {
      throw new ConfigError('请填写已登记的 HTTPS 回调地址');
    }
    const { data } = await client.call('/im/bot/takeover/oauth/start', { ...payload, redirectUri: redirect.href });
    return { message: '已生成飞书授权链接', oauth: normalizeOAuth(data, platform) };
  }
  const expectedConfigVersion = text(input.expectedConfigVersion, '授权配置版本', 24);
  if (!/^\d+$/.test(expectedConfigVersion)) throw new ConfigError('授权配置版本格式不正确');
  await client.call('/im/bot/takeover/oauth/complete', {
    ...payload, expectedConfigVersion,
    ...(!dingTalk && { code: text(input.code, '授权码', 4096), state: text(input.state, '授权状态', 256) }),
  });
  return { message: '本人授权已完成，请启用真人接管' };
}

export async function action(input, client) {
  if (input.action === 'applicationSave') {
    const application = input.application;
    const botKey = text(application?.botKey, '应用 ID', 24);
    const expectedConfigVersion = text(application.configVersion, '应用配置版本', 24);
    if (!/^\d+$/.test(botKey) || !/^\d+$/.test(expectedConfigVersion)) throw new ConfigError('应用配置标识格式不正确');
    const { data: current } = await client.call('/im/bot/application/detail', undefined, { botKey });
    if (String(current.configVersion) !== expectedConfigVersion) throw new ConfigError('应用公共配置已变化，请刷新后重试', 'VERSION_CONFLICT');
    const { secret, mcpUrls } = savedCredentials({ platform: current.platform }, application, current);
    const changes = { ...mcpUrls };
    for (const key of Object.keys(current.mcpUrls ?? {})) {
      if (application.mcpUrls?.[key] === '') changes[key] = '';
    }
    const body = {
      botKey, expectedConfigVersion, name: text(application.name, '显示名称', 128),
      description: application.description ?? '',
      autoProcessEnabled: application.autoProcessEnabled === true,
      groupEnabled: application.groupEnabled === true,
      allowedGroupChatIds: ids(application.allowedGroupChatIds),
      ...(secret && secret !== current.secret ? { secret } : {}),
      ...(Object.keys(changes).length ? { mcpUrls: changes } : {}),
    };
    await client.call('/im/bot/application/update', body);
    return { message: '应用公共配置已保存' };
  }
  if (['personalOAuthStart', 'personalOAuthComplete'].includes(input.action)) {
    const botKey = text(input.botKey, '真人配置 ID', 24);
    if (!/^\d+$/.test(botKey)) throw new ConfigError('真人配置 ID 格式不正确');
    const { data } = await client.call('/im/bot/takeover/detail', undefined, { botKey });
    if (!data?.botKey) throw new ConfigError('未取得本人真人配置');
    const bots = await client.pages('/im/bot/page', { botKey: input.presetId?.replace(/^member-/, '') });
    const original = bots.find(entry => 'member-' + entry.botKey === input.presetId);
    if (!['DINGTALK', 'FEISHU'].includes(original?.platform)) throw new ConfigError('该操作不适用于此机器人');
    return authorize(input, client, data, input.action === 'personalOAuthStart', original.platform);
  }

  if (input.action === 'personalBinding') {
    const botKey = text(input.botKey, '机器人 ID', 24);
    if (!/^\d+$/.test(botKey)) throw new ConfigError('机器人 ID 格式不正确');
    const bots = await client.pages('/im/bot/page', { botKey });
    const bot = bots.find(entry => String(entry.botKey) === botKey);
    if (!bot?.authorized || !bot.enabled) throw new ConfigError('请先联系管理员授权并启用机器人');
    if (bot.bound) return { message: '该账号已绑定' };
    const { data } = await client.call('/im/bot/binding/create-code', { botKey });
    return { message: '已生成绑定码', binding: { bindingText: data.bindingText || '绑定 ' + data.code, expiresAt: data.expiresAt } };
  }

  const preset = lookupPreset(input.presetId);
  const options = optionsFor(input);
  const owner = options.ownerUserId;
  const bot = await client.find(preset);
  if (!bot) throw new ConfigError('请先创建此机器人');
  if (input.action === 'stopWecomIdentity') {
    if (preset.platform !== 'WECOM') throw new ConfigError('该操作仅适用于企微');
    const active = activeWecomIdentity(bot);
    if (!active || active.botKey !== input.activeBotKey || active.configVersion !== input.activeBotConfigVersion) {
      throw new ConfigError('当前启用身份已变化，请刷新状态', 'VERSION_CONFLICT');
    }
    await client.toggle(active, false);
    return { message: '当前企微身份已停用' };
  }
  if (input.action === 'grant') {
    await ensureGrant(client, bot.botKey, owner);
    return { message: '已授权工作台账号 ' + owner };
  }
  if (input.action === 'binding') {
    await client.requireSelf(owner);
    const users = await client.pages(root + 'user/page', { botKey: bot.botKey });
    const user = users.find(user => user.userId === owner);
    if (user?.authorized && user.bound) return { message: '该账号已绑定' };
    if (!user?.authorized) await client.call(root + 'user/grant', { botKey: bot.botKey, targetUserId: owner });
    if (!bot.enabled && bot.identityMode === 'USER') throw new ConfigError('请先恢复原真人配置运行');
    if (!bot.enabled) await client.toggle(bot, true);
    const { data } = await client.call('/im/bot/binding/create-code', { botKey: bot.botKey });
    return { message: '已生成绑定码', binding: { bindingText: data.bindingText || '绑定 ' + data.code, expiresAt: data.expiresAt } };
  }
  const profile = options.identityMode === 'USER' ? await client.profile(bot.botKey, owner) : null;
  if (options.identityMode === 'USER' && !profile) throw new ConfigError('请先保存该人员的接管配置');
  const current = profile ? await client.detail(profile.botKey) : bot;
  if ((current.identityMode || 'BOT') !== options.identityMode) throw new ConfigError('请先选择已保存的身份模式');
  if (input.action !== 'oauthComplete' && input.configVersion && current.configVersion !== input.configVersion) {
    throw new ConfigError('配置已变化，请先刷新状态', 'VERSION_CONFLICT');
  }
  if (['enable', 'disable'].includes(input.action)) {
    if (input.action === 'enable' && preset.platform === 'WECOM') {
      requireVacantWecomIdentity(bot, current.botKey, options.identityMode);
    }
    await client.toggle(current, input.action === 'enable');
    return { message: input.action === 'enable' ? '机器人已启用' : '机器人已停用' };
  }
  if (!['DINGTALK', 'FEISHU'].includes(preset.platform) || !profile || !['oauthStart', 'oauthComplete'].includes(input.action)) throw new ConfigError('该操作不适用于此机器人');
  await client.requireSelf(owner);
  return authorize(input, client, current, input.action === 'oauthStart', preset.platform);
}
