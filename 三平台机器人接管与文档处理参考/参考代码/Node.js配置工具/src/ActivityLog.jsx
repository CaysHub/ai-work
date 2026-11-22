import React from 'react';

export function ActivityLog({ entries, clear, expanded, close }) {
  return <aside className={'activity pane' + (expanded ? ' expanded' : '')} aria-label="操作记录">
    <div className="pane-heading">
      <h2>操作记录 <span className="count">{entries.length}</span></h2>
      <div className="actions">
        <button className="button quiet" onClick={clear} disabled={!entries.length}>清空</button>
        <button className="button quiet mobile-only" onClick={close}>关闭</button>
      </div>
    </div>
    <div className="log-list" aria-live="polite">
      {!entries.length && <div className="log-empty"><i aria-hidden="true" /><span>暂无操作记录</span></div>}
      {entries.map(entry => <article key={entry.id} className={'log ' + (entry.error ? 'failed' : '')}>
        <header><span>{entry.title}</span><time>{entry.time}</time></header>
        <p>{entry.message}</p>
        {entry.steps?.length > 0 && <details>
          <summary>{entry.steps.length} 个接口步骤</summary>
          <ul>{entry.steps.map((step, index) => <li key={index}>
            <span className={step.success ? 'step-good' : 'step-bad'}>{step.success ? '成功' : '失败'}</span>
            <code>{step.method} {step.path}</code>
            <small>{[step.httpStatus, step.code].filter(Boolean).join(' · ')}</small>
          </li>)}</ul>
        </details>}
      </article>)}
    </div>
  </aside>;
}
