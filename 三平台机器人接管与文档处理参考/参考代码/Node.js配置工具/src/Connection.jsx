import React from 'react';

export function Connection({ value, change, connect, result, ownerId, busy, loading, error }) {
  function field(key, label, props = {}) {
    return <label className="field"><span>{label}</span>
      <input value={value[key]} onChange={e => change(key, e.target.value)} disabled={busy} {...props} />
    </label>;
  }
  const users = new Map((result?.bots || []).flatMap(({ users }) => users.map(user => [user.userId, user.userName])));
  return <aside className="connection pane" aria-label="连接设置">
    <div className="pane-heading"><h2>连接设置</h2></div>
    <div className="pane-body">
      <section>
        <h3>目标环境</h3>
        {field('target', '工作台地址', { type: 'text', spellCheck: false })}
        {field('tenantId', '管理租户', { type: 'text', spellCheck: false })}
        <details className="compact">
          <summary>后端路径</summary>
          {field('prefix', '服务路径', { type: 'text', spellCheck: false })}
        </details>
        <button className="button primary wide" onClick={connect} disabled={busy || loading || !value.target.trim() || !value.tenantId.trim()}>
          {loading ? '正在连接…' : '连接 / 刷新'}
        </button>
        {result && <div className="connection-account"><span className="dot" />{result.user.userName} <span className="muted">{result.user.userId}</span></div>}
        {error && <p className="error-note" role="alert">{error}</p>}
      </section>
      {!result?.member && <section>
        <h3>绑定与接管</h3>
        <label className="field"><span>执行账号</span>
          <input value={value.ownerUserId || ownerId || ''} onChange={e => change('ownerUserId', e.target.value)}
            list="accounts" placeholder="随当前浏览器账号识别" disabled={busy} />
          <datalist id="accounts">{[...users].map(([id, name]) => <option key={id} value={id}>{name}</option>)}</datalist>
        </label>
      </section>}
    </div>
  </aside>;
}
