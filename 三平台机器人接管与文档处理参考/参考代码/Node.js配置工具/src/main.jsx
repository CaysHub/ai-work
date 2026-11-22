import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { localApi, botDraft } from './api';
import { Connection } from './Connection';
import { ActivityLog } from './ActivityLog';
import { BotForm, platformNames, status } from './BotForm';
import './style.css';

const defaults = {
  target: 'https://workbench.example.com', prefix: '/servyou-work-backend',
  tenantId: '', ownerUserId: '',
};
function readSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem('im-bot-config:target') || '{}');
    return { ...defaults, target: saved.target ?? defaults.target, prefix: saved.prefix ?? defaults.prefix, tenantId: saved.tenantId ?? defaults.tenantId };
  } catch {
    return defaults;
  }
}

function availableBots(bootstrap, result) {
  const extra = result.bots.filter(record => record.member).map(record => ({
    id: record.presetId, name: record.application?.name || record.bot.name, platform: record.bot.platform,
    appId: record.application?.externalAppId || record.bot.externalAppId || record.bot.botKey,
  }));
  if (result.member) return extra;
  return [...bootstrap.presets.map(preset => ({
    ...preset, name: result.bots.find(record => record.presetId === preset.id)?.application?.name || preset.name,
  })), ...extra];
}

const explanations = { personalOAuthStart: '发起本人授权', personalOAuthComplete: '完成本人授权', grant: '账号授权', personalBinding: '账号绑定', binding: '账号绑定', enable: '启用', disable: '停用', stopWecomIdentity: '停用当前身份', oauthStart: '发起本人授权', oauthComplete: '完成本人授权', applicationSave: '保存公共配置' };

function App() {
  const [bootstrap, setBootstrap] = useState(null);
  const [settings, setSettings] = useState(readSettings);
  const [requested, setRequested] = useState(settings);
  const [active, setActive] = useState('');
  const [data, setData] = useState(null);
  const [drafts, setDrafts] = useState({});
  const [outputs, setOutputs] = useState({});
  const [entries, setEntries] = useState([]);
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [mobileLog, setMobileLog] = useState(false);
  const [mobileConnection, setMobileConnection] = useState(false);

  useEffect(() => {
    localApi('bootstrap').then(result => {
      setBootstrap(result);
      setActive(result.presets[0]?.id ?? '');
    }).catch(cause => setError(cause.message));
  }, []);

  function log(title, message, steps, error = false) {
    const time = new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date());
    setEntries(items => [{ id: crypto.randomUUID(), title, message, steps, error, time }, ...items].slice(0, 100));
  }

  useEffect(() => {
    if (!bootstrap || !requested.target.trim() || !requested.tenantId.trim()) return;
    const ctrl = new AbortController();
    setLoading(true);
    const timer = window.setTimeout(async () => {
      try {
        const result = await localApi('inspect', bootstrap.token, requested, ctrl.signal);
        if (ctrl.signal.aborted) return;
        setData(result);
        const tabs = availableBots(bootstrap, result);
        setActive(id => tabs.some(bot => bot.id === id) ? id : tabs[0]?.id ?? '');
        setDrafts(Object.fromEntries(tabs.map(preset => [
          preset.id, botDraft(preset, result.bots.find(bot => bot.presetId === preset.id)),
        ])));
        setError('');
        log('读取配置', '已读取 ' + result.bots.filter(bot => bot.bot).length + ' 个机器人配置', result.steps);
      } catch (cause) {
        if (ctrl.signal.aborted) return;
        setData(null);
        setError(cause.message);
        log('读取配置', cause.message, cause.steps, true);
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    }, 400);
    return () => { clearTimeout(timer); ctrl.abort(); };
  }, [bootstrap, requested, revision]);

  function resetConnection() {
    setData(null);
    setDrafts({});
    setOutputs({});
    setError('');
    setLoading(false);
  }

  function changeSetting(key, value) {
    setSettings(previous => ({
      ...previous, [key]: value,
      ...(key === 'ownerUserId' ? {} : { ownerUserId: '' }),
    }));
    resetConnection();
  }

  function connect() {
    const { target, prefix, tenantId } = settings;
    try { localStorage.setItem('im-bot-config:target', JSON.stringify({ target, prefix, tenantId })); } catch {}
    resetConnection();
    setRequested(settings);
    setRevision(number => number + 1);
  }

  const tabs = data && bootstrap ? availableBots(bootstrap, data) : (bootstrap?.presets ?? []);
  const preset = tabs.find(entry => entry.id === active);
  const record = data?.bots.find(entry => entry.presetId === active);
  const member = data?.member || record?.member;
  const draft = preset && (drafts[active] ?? botDraft(preset, record));
  function changeDraft(key, value) {
    if (key === 'identityMode' && preset.platform === 'WECOM') {
      const next = botDraft(preset, record, value);
      setDrafts(previous => ({ ...previous, [active]: next }));
      return;
    }
    setDrafts(previous => ({ ...previous, [active]: { ...draft, [key]: value, ...(key === 'identityMode' ? { secret: '' } : {}) } }));
  }

  async function mutate(name, details, clearSaved = false) {
    if (busy || loading || !preset || !record) return;
    const operation = details.action ? explanations[details.action] : '保存配置';
    const title = preset.name + ' · ' + operation;
    setBusy(true);
    try {
      const result = await localApi(name, bootstrap.token, {
        ...requested, ...details, presetId: preset.id,
        configVersion: (draft.identityMode === 'BOT' ? record.rootBot || record.bot : record.bot)?.configVersion,
        options: details.action === 'applicationSave' ? undefined : { ...draft, ownerUserId: data.ownerUserId },
      });
      setOutputs(previous => ({ ...previous, [active]: result }));
      log(title, result.message, result.steps);
      if (clearSaved) {
        setDrafts(previous => ({ ...previous, [active]: { ...previous[active], secret: '', mcpUrls: {} } }));
        await refreshSaved(active, false, preset.platform === 'WECOM' ? draft.identityMode : undefined);
      } else if (['personalBinding', 'grant', 'binding', 'enable', 'disable', 'stopWecomIdentity', 'oauthStart', 'oauthComplete', 'applicationSave'].includes(details.action)) {
        await refreshSaved(active, ['personalBinding', 'grant', 'binding', 'oauthStart', 'stopWecomIdentity'].includes(details.action));
      }
    } catch (cause) {
      log(title, cause.message, cause.steps, true);
      if (cause.code === 'IM_BOT_WECOM_IDENTITY_CONFLICT' || details.action === 'stopWecomIdentity' && cause.code === 'VERSION_CONFLICT') {
        await refreshSaved(active, true);
      } else if (cause.steps?.some(step => step.success && /\/(create|update|disable|enable|grant)$/.test(step.path))) await refreshSaved(active);
    } finally {
      setBusy(false);
    }
  }

  async function refreshSaved(id, keepDraft = false, identityMode) {
    setLoading(true);
    try {
      const result = await localApi('inspect', bootstrap.token, requested);
      setData(result);
      const bot = availableBots(bootstrap, result).find(entry => entry.id === id);
      if (!keepDraft && bot) setDrafts(previous => ({
        ...previous, [id]: botDraft(bot, result.bots.find(entry => entry.presetId === id), identityMode),
      }));
    } catch (cause) {
      setData(null);
      setError(cause.message);
      log('刷新状态', cause.message, cause.steps, true);
    } finally {
      setLoading(false);
    }
  }

  async function runAction(action, value = {}) {
    if (action === 'binding' && member) {
      await mutate('action', { action: 'personalBinding', botKey: record.bot.botKey });
      return;
    }
    if (action === 'oauthComplete') {
      try {
        const current = outputs[active]?.oauth;
        const completeAction = member ? 'personalOAuthComplete' : action;
        if (preset.platform === 'DINGTALK') {
          if (!current) return;
          await mutate('action', { action: completeAction, botKey: current.botKey, expectedConfigVersion: current.configVersion });
          return;
        }
        const callback = new URL(value.callbackUrl);
        const registered = new URL(value.redirectUri);
        const state = callback.searchParams.get('state');
        const code = callback.searchParams.get('code');
        if (!current || !code || !state || state !== new URL(current.authorizationUrl).searchParams.get('state') ||
            callback.origin !== registered.origin || callback.pathname !== registered.pathname) throw new Error();
        await mutate('action', { action: completeAction, botKey: current.botKey || value.botKey, code, state, expectedConfigVersion: current.configVersion });
      } catch {
        log('飞书本人授权', '回跳地址与当前授权不一致，请检查后重试', undefined, true);
      }
      return;
    }
    await mutate('action', { action: member && action === 'oauthStart' ? 'personalOAuthStart' : action, ...value });
  }

  function switchTab(event, index) {
    const next = { ArrowRight: (index + 1) % tabs.length, ArrowLeft: (index - 1 + tabs.length) % tabs.length, Home: 0, End: tabs.length - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    setActive(tabs[next].id);
    document.getElementById('tab-' + tabs[next].id)?.focus();
  }

  const locked = busy || loading;
  return <div className={'app' + (mobileConnection ? ' show-connection' : '')}>
    <header className="topbar">
      <div className="brand"><span aria-hidden="true">IM</span><h1>机器人配置</h1></div>
      <div className="actions">
        {loading && <span className="top-status" role="status">读取配置中…</span>}
        {busy && <span className="top-status" role="status">正在执行操作…</span>}
        <button className="button quiet mobile-only" onClick={() => setMobileConnection(value => !value)}>连接设置</button>
        <button className="button quiet mobile-only" onClick={() => setMobileLog(true)}>操作记录 {entries.length || ''}</button>
      </div>
    </header>
    <main className="workspace">
      <Connection value={settings} change={changeSetting} ownerId={data?.ownerUserId} result={data}
        connect={connect} busy={busy || loading} loading={loading} error={error} />
      <section className="editor pane" aria-label="机器人配置">
        {bootstrap && <div className="tabs" role="tablist" aria-label="机器人">
          {tabs.map((bot, index) => {
            const tone = status(data?.bots.find(entry => entry.presetId === bot.id));
            return <button key={bot.id} id={'tab-' + bot.id} role="tab"
              aria-selected={active === bot.id} aria-controls={'panel-' + bot.id}
              tabIndex={active === bot.id ? 0 : -1} onKeyDown={event => switchTab(event, index)} onClick={() => setActive(bot.id)}>
              <span className={'tab-icon ' + bot.platform.toLowerCase()}>{platformNames[bot.platform]}</span>
              <span>{bot.name}</span><i className={tone.kind} aria-label={tone.label} />
            </button>;
          })}
        </div>}
        {data?.member && !tabs.length && <p className="empty-config">当前租户暂无机器人应用。</p>}
        {preset && <BotForm member={member} key={preset.id + ':' + (data?.ownerUserId ?? 'offline')} preset={preset} record={record} draft={draft}
          change={changeDraft} ownerId={member ? data?.user.userId : data?.ownerUserId} busy={locked} caps={bootstrap.capabilities}
          save={enableAfter => mutate('apply', { enableAfter }, true)} run={runAction} output={outputs[active]} />}
      </section>
      <ActivityLog entries={entries} clear={() => setEntries([])} expanded={mobileLog} close={() => setMobileLog(false)} />
    </main>
  </div>;
}

createRoot(document.getElementById('root')).render(<App />);
