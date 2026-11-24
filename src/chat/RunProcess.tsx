import { BulbOutlined, ToolOutlined } from '@ant-design/icons';
import type { ChatRun } from '../../shared/types.ts';
import { Status } from '../common/Status.tsx';
import { toolNames } from './chat.config.ts';

export function RunProcess({ run }: { run: ChatRun }) {
  const items = [
    ...(run.progress_log ?? []).map((progress) => ({ kind: 'progress' as const, at: progress.at, progress })),
    ...run.tool_log.map((tool) => ({ kind: 'tool' as const, at: tool.started ?? run.created, tool })),
  ].sort((a, b) => a.at - b.at);
  if (!items.length) return null;
  return <details className="run-process">
    <summary><span>执行过程</span><small>{items.length} 项</small></summary>
    <div className="run-process-items">
      {items.map((item) => item.kind === 'progress'
        ? <details key={'progress-' + item.progress.id} className="run-process-step">
          <summary><BulbOutlined /><span>{item.progress.title}</span><small>分析摘要</small></summary>
          <p>{item.progress.summary}</p>
        </details>
        : <details key={'tool-' + item.tool.id} className="run-process-step">
          <summary><ToolOutlined /><span>{toolNames[item.tool.name] ?? item.tool.name}</span><Status value={item.tool.state} /></summary>
          <div className="run-tool-body">
            {item.tool.input == null && item.tool.output == null && item.tool.state !== 'running' && <p>此记录仅保存了工具状态。</p>}
            {item.tool.input != null && <section><b>输入</b><pre>{item.tool.input}</pre></section>}
            {item.tool.output != null && <section><b>{item.tool.state === 'failed' ? '错误' : '输出'}</b><pre>{item.tool.output}</pre></section>}
            {item.tool.state === 'running' && <p>正在等待工具返回</p>}
          </div>
        </details>)}
    </div>
  </details>;
}
