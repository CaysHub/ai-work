import React, { useState } from 'react';

export const platformNames = { DINGTALK: '钉钉', WECOM: '企微', FEISHU: '飞书' };
export const appLabels = { DINGTALK: 'Client ID', WECOM: 'BotID', FEISHU: 'AppID' };
const statusNames = { ONLINE: '在线', DISABLED: '已停用', ERROR: '连接异常', CONNECTING: '连接中', OFFLINE: '离线' };
export function status(record) {
  if (!record) return { label: '未连接', kind: 'neutral' };
  if (!record.bot) return { label: '未配置', kind: 'neutral' };
  if (record.bot.connectionState === 'ERROR') return { label: '连接异常', kind: 'bad' };
  if (!record.bot.enabled) return { label: '已停用', kind: 'neutral' };
  return { label: statusNames[record.bot.connectionState] || '待连接', kind: record.bot.connectionState === 'ONLINE' ? 'good' : 'warn' };
}

function ApplicationForm({ application, caps, busy, save, savedMcpUrls }) {
  const [draft, setDraft] = useState(() => ({
    ...application,
    allowedGroupChatIds: (application.allowedGroupChatIds ?? []).join('\n'),
  }));
  const change = (key, value) => setDraft(current => ({ ...current, [key]: value }));
  const field = (key, label, readOnly = false) => <label className="field">
    <span>{label}</span>
    <input value={draft[key] ?? ''} onChange={event => change(key, event.target.value)} readOnly={readOnly}
      autoComplete="off" spellCheck={false} />
  </label>;
  const area = (label, value, onChange) => <label className="field">
    <span>{label}</span>
    <textarea rows="2" value={value ?? ''} onChange={event => onChange(event.target.value)} autoComplete="off" spellCheck={false} />
  </label>;
  const missingMcpUrls = Object.fromEntries(Object.entries(savedMcpUrls ?? {}).filter(([code]) => !draft.mcpUrls?.[code]));
  return <form className="form-section" onSubmit={event => { event.preventDefault(); save(draft); }}>
    <div className="section-heading"><h3>应用公共配置</h3></div>
    <fieldset disabled={busy}>
      <div className="form-grid conversation-grid">
        {field('name', '应用名称')}
        {field('externalAppId', appLabels[application.platform], true)}
        {area(application.platform === 'WECOM' ? 'Bot Secret' : application.platform === 'FEISHU' ? 'App Secret' : 'Client Secret',
          draft.secret, value => change('secret', value))}
        {area('应用说明', draft.description, value => change('description', value))}
      </div>
      <div className="checks">
        <label className="check"><input type="checkbox" checked={draft.autoProcessEnabled}
          onChange={event => change('autoProcessEnabled', event.target.checked)} />自动处理并回复</label>
        <label className="check"><input type="checkbox" checked={draft.groupEnabled}
          onChange={event => change('groupEnabled', event.target.checked)} />允许群聊</label>
      </div>
      {area('应用允许的群聊', draft.allowedGroupChatIds, value => change('allowedGroupChatIds', value))}
      {application.platform === 'WECOM' && <div className="form-grid conversation-grid">
        {Object.entries(caps).map(([code, name]) => <React.Fragment key={code}>
          {area(name + ' MCP', draft.mcpUrls?.[code], value => change('mcpUrls', { ...draft.mcpUrls, [code]: value }))}
        </React.Fragment>)}
      </div>}
      <div className="actions">
        <button className="button primary" type="submit">保存公共配置</button>
        {Object.keys(missingMcpUrls).length > 0 && <button type="button"
          className="button" onClick={() => change('mcpUrls', { ...draft.mcpUrls, ...missingMcpUrls })}>填入已保存的 MCP 地址</button>}
      </div>
    </fieldset>
  </form>;
}

export function BotForm({ preset, record, draft, change, ownerId, busy, caps, run, save, output, member }) {
  const [redirectUri, setRedirectUri] = useState('');
  const [callbackUrl, setCallbackUrl] = useState('');
  const [profileKey, setProfileKey] = useState('');
  const [copied, setCopied] = useState(false);
  const bot = draft.identityMode === 'BOT' ? record?.rootBot || record?.bot : record?.bot;
  const person = record?.users.find(user => user.userId === ownerId);
  const connection = status(record && { ...record, bot });
  const isUser = draft.identityMode === 'USER';
  const showIdentityFields = isUser || !record?.application;
  const dingTalk = preset.platform === 'DINGTALK';
  const wecomTakeover = preset.platform === 'WECOM' && isUser;
  const activeIdentity = record?.activeIdentity;
  const activeMode = activeIdentity?.identityMode || record?.application?.activeIdentityMode;
  const selectedKey = isUser ? record?.takeover?.botKey : record?.rootBot?.botKey;
  const identityConflict = activeIdentity && (activeIdentity.botKey !== selectedKey || activeIdentity.identityMode !== draft.identityMode);
  const modeChangeNeedsStop = identityConflict && !isUser && activeIdentity.botKey === selectedKey;
  const activeOwner = activeIdentity?.ownerUserId;
  const activeName = record?.users.find(user => user.userId === activeOwner)?.userName || activeOwner;
  const activeLabel = activeMode === 'BOT' ? '普通机器人' : activeName ? `真人接管 · ${activeName}` : '真人接管';
  const identityHint = '先停用当前企微身份，再启用所选配置';
  const needsWecomMessage = wecomTakeover && record?.takeover?.authorizationReady !== true;
  const missingWecomMessage = needsWecomMessage && !draft.mcpUrls.msg?.trim();
  const wecomEnableHint = record?.application?.mcpUrls?.msg
    ? `${ownerId} 尚未配置真人消息授权。已保存的共享地址可用于应用工具。`
    : record?.existingWecomMessageConfig
      ? `原机器人已保存旧版真人消息地址。可用原接管账号登录查看。${ownerId} 的接管配置尚未保存消息授权。`
      : `${ownerId} 的企微接管缺少消息授权。`;
  const applicationNotice = bot && !record?.application && (
    record?.applicationAvailable === undefined ? '当前独立页进程未加载公共配置接口，请重新启动独立页。'
      : record.applicationAvailable === false ? '目标工作台尚未提供应用公共配置接口，请确认后端已发布。'
        : '该应用的公共配置需使用所属租户的账号读取。'
  );
  const canControl = Boolean(bot) && draft.identityMode === (bot.identityMode || 'BOT');
  const canAct = !busy && Boolean(record);
  const groups = new Map((record?.groups || []).filter(group => group.externalChatId)
    .map(group => [group.externalChatId, group.name || group.externalChatId]));
  for (const id of draft.allowedGroupChatIds) if (!groups.has(id)) groups.set(id, id);
  const input = (key, label, attrs = {}) => <label className="field">
    <span>{label}</span><input value={draft[key]} onChange={e => change(key, e.target.value)} {...attrs} />
  </label>;
  const check = (key, label) => <label className="check">
    <input type="checkbox" checked={draft[key]} onChange={e => change(key, e.target.checked)} />{label}
  </label>;
  const area = (key, label, placeholder) => <label className="field"><span>{label}</span>
    <textarea rows="3" value={draft[key]} onChange={e => change(key, e.target.value)} placeholder={placeholder} spellCheck="false" />
  </label>;

  return <div className="bot-panel" id={'panel-' + preset.id} role="tabpanel" aria-labelledby={'tab-' + preset.id}>
    <div className="bot-heading">
      <div className={'platform-icon ' + preset.platform.toLowerCase()}>{platformNames[preset.platform]}</div>
      <div className="bot-name"><h2>{preset.name}</h2><code>{member && !record?.application ? "机器人 ID" : appLabels[preset.platform]} · {preset.appId}</code></div>
      <span className={'badge ' + connection.kind}><i />{connection.label}</span>
      {!member && <div className="actions bot-actions">
        <button className="button" onClick={() => save(false)} title={modeChangeNeedsStop ? identityHint : undefined}
          disabled={!canAct || modeChangeNeedsStop}>{bot?.enabled ? '保存并停用' : '保存配置'}</button>
        <button className="button primary" onClick={() => save(true)}
          title={identityConflict ? identityHint : missingWecomMessage ? wecomEnableHint : undefined}
          disabled={!canAct || identityConflict || missingWecomMessage}>保存并启用</button>
        {canAct && missingWecomMessage && !identityConflict && <small className="muted">启用需完成企微消息授权</small>}
      </div>}
    </div>
    <div className="bot-scroll">
      <section className="status-grid" aria-label="配置状态">
        <div><span>执行账号</span><strong>{ownerId || '—'}</strong></div>
        <div><span>工作台授权</span><strong className={person?.authorized ? 'positive' : ''}>{person?.authorized ? '已授权' : '未授权'}</strong></div>
        <div><span>账号绑定</span><strong className={person?.bound ? 'positive' : ''}>{person?.bound ? '已绑定' : '未绑定'}</strong></div>
        <div><span>已保存身份</span><strong>{bot ? bot.identityMode === 'USER' ? '真人接管' : '普通机器人' : '—'}</strong></div>
      </section>
      {activeMode && <div className="callout">
        <div className="actions">
          <strong>当前启用 · {activeLabel}</strong>
          {!member && identityConflict && <button className="button" disabled={!canAct}
            onClick={() => run('stopWecomIdentity', { activeBotKey: activeIdentity.botKey, activeBotConfigVersion: activeIdentity.configVersion })}>
            停用当前身份
          </button>}
        </div>
        {identityConflict && <small>{identityHint}。</small>}
      </div>}
      {bot?.lastError && <p className="error-note">{bot.lastError}</p>}
      {applicationNotice && <p className="error-note">{applicationNotice}</p>}
      {record?.application && <ApplicationForm key={record.application.botKey + ':' + record.application.configVersion}
        application={record.application} caps={caps} busy={busy}
        savedMcpUrls={isUser ? record.bot?.mcpUrls : undefined} save={application => run('applicationSave', { application })} />}
      {!member && <fieldset disabled={!canAct}>
        <section className="form-section">
          <div className="section-heading"><h3>{record?.application ? '当前身份配置' : '基础配置'}</h3></div>
          <div className="form-grid">
            {showIdentityFields && input('name', '显示名称', { maxLength: 128 })}
            <label className="field"><span>使用身份</span>
              <select value={draft.identityMode} onChange={e => change('identityMode', e.target.value)}>
                <option value="BOT">普通机器人</option>
                <option value="USER">真人接管</option>
              </select>
            </label>
          </div>
          <div className="checks">
            {showIdentityFields && check('autoProcessEnabled', '自动处理并回复')}
            {showIdentityFields && check('groupEnabled', '允许群聊')}
            {!isUser && check('grantOwner', '保存时授权执行账号')}
          </div>
          {!isUser && !record?.application && <details open={!bot || undefined}>
            <summary>应用密钥</summary>
            {input('secret', preset.platform === 'DINGTALK' ? 'Client Secret' : 'Secret',
              { autoComplete: 'off', spellCheck: false, placeholder: bot ? '留空保留现有密钥' : '新建配置需填写' })}
          </details>}
        </section>
        {(isUser || draft.groupEnabled && !record?.application) && <section className="form-section">
          <div className="section-heading"><h3>会话范围</h3></div>
          {isUser ? <>
            <div className="form-grid conversation-grid">
              {input('externalUserId', dingTalk ? '钉钉员工 userid' : preset.platform === 'FEISHU' ? '飞书本人 open_id' : '企微机器人回调 userid',
                { readOnly: true, placeholder: '保存时读取已绑定的账号' })}
              {wecomTakeover ? input('messageUserId', '企微消息 MCP 本人 userid')
                : draft.groupEnabled && input('groupMentionName', '群聊点名名称',
                  { maxLength: 64, placeholder: '群内显示名，用于识别文本 @' })}
            </div>
            <div className="form-grid conversation-grid">
              {area('privateChatIds', '私聊范围', dingTalk ? '每行一个钉钉私聊会话 ID' : preset.platform === 'WECOM' ? '每行一个聊天对方的 MCP userid' : '每行一个飞书私聊 ID')}
              {area('groupChatIds', '群聊范围', '每行一个群聊 ID')}
            </div>
            {wecomTakeover && draft.groupEnabled && input('groupMentionName', '群聊点名名称',
              { maxLength: 64, placeholder: '群内显示名，用于识别文本 @' })}
            {check('discoverEnabled', '自动发现会话')}
          </> : <div className="group-list">
            {groups.size ? [...groups].map(([id, name]) => <label key={id} className="check">
              <input type="checkbox" checked={draft.allowedGroupChatIds.includes(id)} onChange={e =>
                change('allowedGroupChatIds', e.target.checked ? [...draft.allowedGroupChatIds, id] : draft.allowedGroupChatIds.filter(value => value !== id))
              } /><span>{name}<small>{name !== id ? id : ''}</small></span>
            </label>) : <p className="muted">授权用户在群里 @ 机器人后，刷新即可选择。</p>}
          </div>}
        </section>}
        {preset.platform === 'WECOM' && (wecomTakeover || !record?.application) && <section className="form-section">
          <details open>
            <summary>{wecomTakeover ? '企微本人 MCP 授权' : '企微 MCP 授权'}</summary>
            {wecomTakeover && <p className={needsWecomMessage ? 'error-note' : 'muted'}>
              {needsWecomMessage ? wecomEnableHint
                : bot?.mcpUrls === undefined ? '消息授权已保存，使用本人账号及支持地址回填的工作台查看。' : '本人消息 MCP 地址已保存。'}
            </p>}
            <div className="form-grid">{Object.entries(caps).map(([key, name]) => <label key={key} className="field">
              <span>{wecomTakeover && key === 'msg' ? '本人消息 MCP' : name}</span><textarea rows="2" autoComplete="off" spellCheck={false}
                placeholder="授权 URL，留空保留"
                value={draft.mcpUrls[key] || ''} onChange={e => change('mcpUrls', { ...draft.mcpUrls, [key]: e.target.value })} />
            </label>)}</div>
          </details>
        </section>}
      </fieldset>}
      <section className="form-section">
        <div className="section-heading"><h3>绑定与运行</h3></div>
        <div className="actions">
          <button className="button" onClick={() => run('binding')} disabled={!canAct || !bot}>{person?.bound ? '查看绑定' : '绑定账号'}</button>
          {!member && bot && !person?.authorized && <button className="button" disabled={!canAct} onClick={() => run('grant')}>授权执行账号</button>}
          {!member && <button className="button" onClick={() => run(bot?.enabled ? 'disable' : 'enable')}
            title={identityConflict ? identityHint : !bot?.enabled && needsWecomMessage ? wecomEnableHint : undefined}
            disabled={!canAct || !canControl || identityConflict || !bot?.enabled && needsWecomMessage}>
            {bot?.enabled ? '停用' : '启用已保存配置'}
          </button>}
        </div>
        {output?.binding && <div className="callout">
          <p>将绑定文本发送到该机器人的单聊。</p>
          <div className="actions"><code>{output.binding.bindingText}</code><button className="button quiet" onClick={async () => {
            await navigator.clipboard.writeText(output.binding.bindingText);
            setCopied(true);
          }}>{copied ? '已复制' : '复制'}</button></div>
          {output.binding.expiresAt && <small>有效期至 {output.binding.expiresAt}</small>}
        </div>}
        {(isUser || member && person?.bound) && (dingTalk || preset.platform === 'FEISHU') && <details open={output?.oauth ? true : undefined}>
          <summary>{platformNames[preset.platform]}本人授权{record?.takeover?.authorizationReady ? ' · 已授权' : ''}</summary>
          {member ? <label className="field"><span>真人配置 ID</span><input value={profileKey} onChange={e => setProfileKey(e.target.value)}
            placeholder="填写管理员保存的真人配置 ID" /></label>
            : record?.takeover && <p className="muted">真人配置 ID · {record.takeover.botKey}</p>}
          <div className="form-grid">
            {!dingTalk && <label className="field"><span>已登记的 HTTPS 回调地址</span><input value={redirectUri} onChange={e => setRedirectUri(e.target.value)} /></label>}
            <button className="button align-end" onClick={() => run('oauthStart', { redirectUri, botKey: profileKey.trim() })} disabled={!canAct || !(member ? profileKey.trim() : canControl) || !dingTalk && !redirectUri.trim()}>生成授权链接</button>
          </div>
          {output?.oauth && <div className="callout">
            <a href={output.oauth.authorizationUrl} target="_blank" rel="noreferrer">打开{platformNames[preset.platform]}授权</a>
            {!dingTalk && <label className="field"><span>授权回跳地址</span><input value={callbackUrl} onChange={e => setCallbackUrl(e.target.value)} /></label>}
            <button className="button" disabled={!canAct || !dingTalk && !callbackUrl.trim()} onClick={() => run('oauthComplete', { callbackUrl, redirectUri, botKey: profileKey.trim() })}>完成授权</button>
          </div>}
        </details>}
      </section>
    </div>
  </div>;
}
