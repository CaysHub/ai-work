import { errorMessage } from '../../shared/types.ts';
import type { Secrets } from '../common/types.ts';
import { feishuApp, lark } from '../platform/http.ts';
import type { DocumentArgs, DocumentMetadata } from './identifiers.ts';
import { identifier } from './identifiers.ts';

type FeishuConvertedBlock = { table?: { property?: Record<string, unknown> } };
export async function feishuDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
  const token = await feishuApp(secrets);
  const call = <T extends object>(path: string, body?: object) => lark<T>(path, body ? { method: 'POST', token, body } : { token });
  let id = args.documentId;
  let link: string | null = null;
  if (args.action === 'create') {
    const created = await call<{ document?: { document_id?: string; url?: string } }>('/docx/v1/documents', { title: args.title, ...(secrets.folderToken ? { folder_token: secrets.folderToken } : {}) });
    id = identifier(created.document?.document_id);
    link = created.document?.url ?? null;
  }
  if (args.action !== 'read' && args.content) {
    try {
      const blocks = await call<{ first_level_block_ids?: string[]; blocks?: FeishuConvertedBlock[] }>(
        '/docx/v1/documents/blocks/convert', { content_type: 'markdown', content: args.content });
      const children = blocks.first_level_block_ids;
      if (!children?.length || !blocks.blocks?.length) throw new Error('飞书未返回可写入的文档块');
      const descendants = blocks.blocks.map((block) => {
        if (!block.table?.property) return block;
        // Feishu includes merge_info in converted tables and rejects it in descendant writes.
        const { merge_info: _readOnly, ...property } = block.table.property;
        return { ...block, table: { ...block.table, property } };
      });
      await call('/docx/v1/documents/' + id + '/blocks/' + id + '/descendant',
        { children_id: children, descendants, index: -1 });
    } catch (error) {
      throw new Error('飞书文档 ' + id + ' 写入结果待核对。' + errorMessage(error));
    }
  }
  const content = await call<{ content?: string }>('/docx/v1/documents/' + id + '/raw_content');
  if (typeof content.content !== 'string') throw new Error('飞书文档 ' + id + ' 未返回正文');
  if (!link && args.action !== 'read') {
    try {
      const data = await call<{ metas?: { doc_token?: string; url?: string }[] }>('/drive/v1/metas/batch_query', { request_docs: [{ doc_token: id, doc_type: 'docx' }], with_url: true });
      link = data.metas?.find((item) => item.doc_token === id)?.url ?? null;
    } catch { /* An application can have document scope without Drive metadata scope. */ }
  }
  return { documentId: id, url: link, content: content.content };
}
