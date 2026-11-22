import { BulbOutlined, ToolOutlined } from '@ant-design/icons';
import type { ChatRun } from '../shared/types.ts';
import { Status } from './shared.tsx';

const toolNames: Record<string, string> = {
  workspace_write: '保存文件', workspace_read: '读取文件', workspace_list: '查看工作区',
  word_document_create: '生成 Word 文档', attached_document_read: '读取附件',
  skill_read: '读取 Skill 资源', cloud_document: '操作云文档', cloud_document_search: '搜索云文档',
  wecom_document_search: '搜索企微文档', wecom_sheet_read: '读取企微表格',
  reply_document: '准备回复文档', wecom_reply_decision: '提交企微回复决定', mcp_tools: '调用 MCP 工具',
};

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
