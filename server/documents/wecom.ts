import { setTimeout as delay } from 'node:timers/promises';
import type { Secrets } from '../common/types.ts';
import { platformSignal } from '../platform/http.ts';
import { wecomCall } from '../platform/mcp.ts';
import { wecomPlainText } from '../replies/wecom-text.ts';
import { documentConfig } from './documents.config.ts';
import type { DocumentArgs, DocumentMetadata } from './identifiers.ts';

type FeishuConvertedBlock = { table?: { property?: Record<string, unknown> } };
export async function wecomDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
  const { action, documentId: docid, title, content } = args;
  const call = <T>(name: string, params: Record<string, unknown>) => wecomCall<T>(secrets.documentMcp, 'doc', name, params);
  if (action === 'create') return call('doc_create', { doc_type: 'doc',
    doc_name: title.replace(/[<>:"/\\|?*]/g, ' ').trim() || '企微文档',
    content_type: 'text', content: content ? wecomPlainText(content) : '' });
  if (action === 'append') return call('doc_contents_append', { docid, content, content_type: 'markdown' });
  return wecomRead(call, 'doc_contents_get', { docid, content_type: 'markdown' });
}
export async function wecomRead<T>(call: <R>(name: string, params: Record<string, unknown>) => Promise<R>, name: string,
  args: Record<string, unknown>, complete: (value: T) => boolean = (value) => typeof (value as { content?: unknown }).content === 'string') {
  const request = await call<T & { taskid?: string; task_id?: string }>(name + '_submit', args);
  const taskid = request.taskid ?? request.task_id;
  if (!taskid && complete(request)) return request;
  if (!taskid) throw new Error('企微未返回文档读取任务号');
  for (let attempt = 0; attempt < documentConfig.wecomReadAttempts; attempt++) {
    const data = await call<T>(name + '_query', { taskid });
    if (complete(data)) return data;
    if (attempt + 1 < documentConfig.wecomReadAttempts) await delay(documentConfig.wecomReadRetryMs, undefined, { signal: platformSignal.getStore() });
  }
  throw new Error('企微文档读取超时');
}
