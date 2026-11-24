import { DownloadOutlined, FileTextOutlined, PaperClipOutlined, UploadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Card, Drawer, Empty, Input, Select, Space, Spin, Tag, Upload } from 'antd';
import { useEffect, useState } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { bytesPerKiB, bytesPerMiB, documentList, httpPaths } from '../../shared/config.ts';
import type { Chat, DocumentPreview, DocumentRecord } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { CompactTable, markdownTableCells, OverflowText } from '../common/CompactTable.tsx';
import { date } from '../common/format.ts';
import { sourceLabels, sourceOptions } from './documents.config.ts';

const download = (id: string) => httpPaths.api + '/documents/' + encodeURIComponent(id) + '/download';
const inline = (id: string) => httpPaths.api + '/documents/' + encodeURIComponent(id) + '/inline';
function formatBytes(bytes: number) {
  if (bytes < bytesPerKiB) return bytes + ' B';
  return bytes < bytesPerMiB ? (bytes / bytesPerKiB).toFixed(1) + ' KB' : (bytes / bytesPerMiB).toFixed(1) + ' MB';
}

export function DocumentUpload({ chat, maxBytes, compact, disabled, onStart, onFinish }: {
  chat?: string;
  maxBytes?: number;
  compact?: boolean;
  disabled?: boolean;
  onStart?: () => void;
  onFinish: (document?: DocumentRecord) => void;
}) {
  const [pending, setPending] = useState(0);
  const { message } = App.useApp();
  async function upload(file: File) {
    if (maxBytes && file.size > maxBytes) {
      message.error(file.name + ' 超过 ' + formatBytes(maxBytes));
      return;
    }
    setPending((count) => count + 1);
    onStart?.();
    let document: DocumentRecord | undefined;
    try {
      const form = new FormData();
      form.append('file', file);
      const path = '/documents' + (chat ? '?' + new URLSearchParams({ chat }) : '');
      document = await api<DocumentRecord>(path, 'POST', form);
    } catch (error) {
      message.error(errorMessage(error));
    } finally {
      setPending((count) => count - 1);
      onFinish(document);
    }
  }
  return <Upload multiple disabled={disabled} showUploadList={false} beforeUpload={(file) => { void upload(file); return Upload.LIST_IGNORE; }}>
    <Button disabled={disabled} type={compact ? 'text' : 'primary'} icon={compact ? <PaperClipOutlined /> : <UploadOutlined />} loading={pending > 0}>
      {compact ? '上传附件' : '上传文档'}
    </Button>
  </Upload>;
}

export function DocumentTiles({ documents, preview }: { documents: DocumentRecord[]; preview: (document: DocumentRecord) => void }) {
  return <div className="document-tiles">{documents.map((document) => <div className="document-tile" key={document.id}>
    <button type="button" onClick={() => preview(document)}>
      <FileTextOutlined /><span><OverflowText text={document.name} /><small>{formatBytes(document.bytes)}</small></span>
    </button>
    <Button type="text" icon={<DownloadOutlined />} href={download(document.id)} aria-label={'下载 ' + document.name} />
  </div>)}</div>;
}

export function DocumentDrawer({ document, close }: { document: DocumentRecord; close: () => void }) {
  const [preview, setPreview] = useState<DocumentPreview>();
  const [error, setError] = useState('');
  const endpoint = '/documents/' + encodeURIComponent(document.id) + '/preview';
  useEffect(() => {
    let current = true;
    api<DocumentPreview>(endpoint).then((data) => { if (current) setPreview(data); })
      .catch((failure) => { if (current) setError(errorMessage(failure)); });
    return () => { current = false; };
  }, [endpoint]);
  return <Drawer open onClose={close} placement="left" size="large" classNames={{ wrapper: 'document-drawer-wrapper' }}
    title={<span className="document-drawer-title">{document.name}</span>}
    extra={<Button href={download(document.id)} icon={<DownloadOutlined />}>下载</Button>}>
    <div className="document-meta"><Tag>{sourceLabels[document.source]}</Tag>{formatBytes(document.bytes)} · {date(document.created)}</div>
    {error ? <Alert type="error" showIcon title={error} /> : !preview ? <div className="center"><Spin /></div> : <>
      {preview.kind === 'download' && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="此文件可下载后查看" />}
      {preview.kind === 'image' && <img className="document-preview-image" src={inline(document.id)} alt={document.name} />}
      {preview.kind === 'pdf' && <iframe className="document-preview-pdf" src={inline(document.id)} title={document.name} />}
      {'text' in preview && <>
        {preview.truncated && <Alert showIcon type="info" title="当前展示部分内容，可下载查看完整文件" />}
        {preview.kind === 'markdown' ? <div className="answer document-markdown">
          <Markdown remarkPlugins={[remarkGfm]} components={markdownTableCells} skipHtml disallowedElements={['img']}>{preview.text}</Markdown>
        </div> : <pre className="preview document-preview-text">{preview.text}</pre>}
      </>}
    </>}
  </Drawer>;
}

type DocumentPage = { rows: DocumentRecord[]; total: number };
export function Documents({ scope, clearScope, openChat, preview, maxBytes }: {
  scope: Chat | null;
  clearScope: () => void;
  openChat: (id: string) => void;
  preview: (document: DocumentRecord) => void;
  maxBytes?: number;
}) {
  const [query, setQuery] = useState('');
  const [source, setSource] = useState('all');
  const [page, setPage] = useState(1);
  const [revision, setRevision] = useState(0);
  const [result, setResult] = useState<DocumentPage>({ rows: [], total: 0 });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const { message } = App.useApp();
  const path = '/documents?' + new URLSearchParams({
    q: query, source, page: String(page), ...(scope ? { chat: scope.id } : {}),
  });
  useEffect(() => {
    let current = true;
    setLoading(true);
    setError('');
    api<DocumentPage>(path).then((data) => { if (current) setResult(data); })
      .catch((failure) => { if (current) setError(errorMessage(failure)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [path, revision]);
  return <section className="view">
    <header className="view-heading">
      <div><h1>文档管理</h1><p>查看上传的文档和对话生成的文件。</p></div>
      {(!scope || (!scope.archived && !scope.bot)) && <DocumentUpload chat={scope?.id} maxBytes={maxBytes} onFinish={(document) => {
        if (!document) return;
        setRevision((value) => value + 1);
        message.success('已上传 ' + document.name);
      }} />}
    </header>
    <Card className="records">
      <div className="replies-filters">
        <Input.Search aria-label="搜索文档" placeholder="搜索文件名或会话" allowClear maxLength={documentList.queryLength}
          onSearch={(value) => { setQuery(value); setPage(1); }} />
        <Select aria-label="文档来源" value={source} options={sourceOptions} onChange={(value) => { setSource(value); setPage(1); }} />
        {scope && <Tag closable onClose={() => { setPage(1); clearScope(); }}>{scope.title}</Tag>}
      </div>
      {error && <Alert type="error" showIcon title={error} />}
      <CompactTable<DocumentRecord> rowKey="id" dataSource={result.rows} loading={loading}
        locale={{ emptyText: '暂无文档' }} pagination={{ current: page, pageSize: documentList.pageSize, total: result.total, onChange: setPage, hideOnSinglePage: true }}
        columns={[
          { title: '文件名', width: '29%', render: (_, row) => <Button type="link" icon={<FileTextOutlined />}
            className="table-text-button" onClick={() => preview(row)}><OverflowText text={row.name} /></Button> },
          { title: '来源', width: '13%', render: (_, row) => <Tag color={row.source === 'generated' ? 'green' : undefined}>{sourceLabels[row.source]}</Tag> },
          { title: '会话', render: (_, row) => row.chat ? <Button type="link" className="table-text-button"
            onClick={() => { if (row.chat) openChat(row.chat); }}><OverflowText text={row.chat_title ?? '打开会话'} /></Button> : '—' },
          { title: '大小', width: '11%', render: (_, row) => formatBytes(row.bytes) },
          { title: '时间', width: '15%', render: (_, row) => date(row.created) },
          { title: '操作', width: '16%', render: (_, row) => <Space><Button type="link" href={download(row.id)}
            icon={<DownloadOutlined />}>下载</Button></Space> },
        ]} />
    </Card>
  </section>;
}
