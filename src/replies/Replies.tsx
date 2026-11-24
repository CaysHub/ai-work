import { Alert, Button, Card, Input, Modal, Select, Space } from 'antd';
import { useEffect, useState } from 'react';
import { replyList, replyReferenceLength } from '../../shared/config.ts';
import type { ReplyPage, ReplyRecord } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { CompactTable, OverflowText } from '../common/CompactTable.tsx';
import { date } from '../common/format.ts';
import { Sources } from '../common/status.config.ts';
import { Status } from '../common/Status.tsx';
import { filters, replyViewConfig } from './replies.config.ts';

function rowStatus(row: ReplyRecord) {
  return ['rejected', 'expired', 'commanded'].includes(row.review_state ?? '') ? row.review_state : row.reply;
}
const replyText = (row: ReplyRecord) => row.reply_text ?? row.draft ?? row.answer;

function CancelReplyButton({ id, refresh, link = false }: { id: string; refresh: () => void; link?: boolean }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cancel = async () => {
    setBusy(true);
    setError(null);
    try {
      await api('/replies/' + encodeURIComponent(id) + '/cancel', 'POST');
      refresh();
    } catch (failure) {
      setError(errorMessage(failure));
    } finally {
      setBusy(false);
    }
  };
  return <>
    <Button type={link ? 'link' : 'default'} size={link ? 'small' : 'middle'} danger loading={busy} onClick={cancel}
      aria-label="取消本次处理">{link ? '取消' : '取消处理'}</Button>
    {error && <Alert type="error" showIcon title={error} />}
  </>;
}

function ReplyDetail({ id, close, openChat, revision, refresh }: {
  id: string; close: () => void; openChat: (id: string) => void; revision: number; refresh: () => void;
}) {
  const [row, setRow] = useState<ReplyRecord | null>(null);
  const [error, setError] = useState<string | null>(null);
  const path = '/replies/' + encodeURIComponent(id);
  useEffect(() => {
    let live = true;
    const load = () => api<ReplyRecord>(path).then((value) => {
      if (live) { setRow(value); setError(null); }
    }).catch((failure) => { if (live) setError(errorMessage(failure)); });
    load();
    const timer = setInterval(load, replyViewConfig.detailRefreshMs);
    return () => { live = false; clearInterval(timer); };
  }, [path, revision]);
  return <Modal open title="回复详情" onCancel={close} width={replyViewConfig.detailWidth} footer={[
    Boolean(row?.can_cancel) && <CancelReplyButton key="cancel" id={id} refresh={refresh} />,
    row && <Button key="chat" onClick={() => { close(); openChat(row.chat); }}>打开原会话记录</Button>,
  ].filter(Boolean)}>
    {error && <Alert type="error" showIcon title={error} />}
    {row && <div className="reply-detail">
      <Space wrap><b>{row.title}</b><Status value={rowStatus(row)} /><span>{date(row.created)}</span></Space>
      <h4>原消息</h4><p>{row.prompt}</p>
      {row.reply === 'skipped' ? <><h4>无需处理的原因</h4><p>{row.answer}</p></> : <>
        {row.error && <Alert type={row.reply === 'canceled' ? 'info' : 'error'} showIcon title={row.error} />}
        {row.reply === 'canceled' && row.sent_parts > 0 && <p>取消前已有 {row.sent_parts} 段获平台受理。</p>}
        <h4>{row.reply === 'accepted' && row.reply_text ? '实际发送内容' : row.reply === 'canceled' ? '取消前的回复草稿' : '回复内容'}</h4>
        <p>{replyText(row) ?? '暂无回复内容'}</p>
      </>}
      {row.document && <details>
        <summary>{row.document.url ? '云文档' : '云文档草稿'} · {row.document.title}</summary>
        {row.document.url && <a href={row.document.url} target="_blank" rel="noreferrer">打开云文档</a>}
        <p>{row.document.content}</p>
      </details>}
      {row.reply === 'awaiting_review' && <Alert type="info" showIcon
        title={'在' + (row.source === 'wecom' ? '企微' : Sources[row.source]) + '的接管机器人私聊发送「接管 ' + row.id.slice(0, replyReferenceLength) + '」'} />}
      {row.sent_at && <small>平台受理时间 {date(row.sent_at)}</small>}
    </div>}
  </Modal>;
}
export function Replies({ reviewId, showReply, dismissReply, openChat }: {
  reviewId: string | null; showReply: (id: string) => void; dismissReply: () => void; openChat: (id: string) => void;
}) {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('all');
  const [bot, setBot] = useState('all');
  const [page, setPage] = useState(1);
  const [data, setData] = useState<ReplyPage>({ rows: [], total: 0, bots: [] });
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const path = '/replies?' + new URLSearchParams({ q: query, state: status, botId: bot, page: String(page) });
  useEffect(() => {
    let live = true;
    const load = () => api<ReplyPage>(path).then((result) => {
      if (live) { setData(result); setError(null); }
    }).catch((failure) => { if (live) setError(errorMessage(failure)); });
    load();
    const timer = setInterval(load, replyViewConfig.listRefreshMs);
    return () => { live = false; clearInterval(timer); };
  }, [path, revision]);
  return <section className="view">
    <header className="view-heading"><div><h1>回复记录</h1><p>查询机器人来信、接管待办和实际发出的回复。</p></div></header>
    <Card className="records">
      <div className="replies-filters">
        <Input.Search aria-label="搜索回复记录" placeholder="搜索来信、回复正文或会话" allowClear maxLength={replyList.queryLength}
          onSearch={(value) => { setPage(1); setQuery(value); }} />
        <Select aria-label="回复状态" options={filters} value={status} onChange={(value) => { setPage(1); setStatus(value); }} />
        <Select aria-label="按机器人筛选" className="reply-bot-filter" value={bot}
          options={[{ value: 'all', label: '全部机器人', title: '' }, ...(data.bots ?? []).map((item) => ({
            value: item.id, label: item.name + ' · ' + Sources[item.platform], title: '',
          }))]}
          labelRender={({ label }) => <OverflowText text={String(label)} />}
          optionRender={({ label }) => <OverflowText text={String(label)} />}
          onChange={(value) => { setPage(1); setBot(value); }} />
      </div>
      {error && <Alert type="error" showIcon title={error} />}
      <CompactTable<ReplyRecord> rowKey="id" dataSource={data.rows} pagination={{
        current: page, pageSize: replyList.pageSize, total: data.total, onChange: setPage, showSizeChanger: false,
      }} columns={[
        { title: '会话', width: '18%', render: (_, row) => <><OverflowText text={row.title}><b>{row.title}</b></OverflowText><OverflowText text={Sources[row.source]} /></> },
        { title: '来信', dataIndex: 'prompt' },
        { title: '回复', render: (_, row) => row.reply === 'skipped' ? row.answer ?? '—' :
          row.reply === 'canceled' ? row.error ?? '—' : replyText(row) ?? '—' },
        { title: '状态', width: '14%', render: (_, row) => <Status value={rowStatus(row)} /> },
        { title: '时间', width: '16%', render: (_, row) => date(row.sent_at ?? row.created) },
        { title: '操作', width: '18%', render: (_, row) => <div className="reply-actions">
          <Button type="link" size="small" onClick={() => showReply(row.id)}>{row.reply === 'awaiting_review' ? '草稿' : '详情'}</Button>
          {Boolean(row.can_cancel) && <CancelReplyButton id={row.id} refresh={refresh} link />}
        </div> },
      ]} />
    </Card>
    {reviewId && <ReplyDetail key={reviewId} id={reviewId} close={dismissReply} openChat={openChat} revision={revision} refresh={refresh} />}
  </section>;
}
