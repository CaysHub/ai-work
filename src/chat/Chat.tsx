import { ArrowRightOutlined, ArrowUpOutlined, ReadOutlined, StopOutlined, ThunderboltFilled, ToolOutlined } from '@ant-design/icons';
import type { Input } from 'antd';
import { Alert, Button, Select, Spin, Tag } from 'antd';
import type { ComponentRef, RefObject } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { chatLimits } from '../../shared/config.ts';
import type { Chat, ChatRun, DocumentRecord } from '../../shared/types.ts';
import { markdownTableCells, OverflowText, skillLabelPreviews } from '../common/CompactTable.tsx';
import { PreviewTextArea } from '../common/ContentPreview.tsx';
import { Status } from '../common/Status.tsx';
import { date } from '../common/format.ts';
import { Sources } from '../common/status.config.ts';
import type { SkillOption } from '../common/types.ts';
import { DocumentTiles, DocumentUpload } from '../documents/Documents.tsx';
import { RunProcess } from './RunProcess.tsx';
import { activeRunStates, chatViewConfig, suggestions } from './chat.config.ts';

export function ChatTranscript({ chatId, chat, scrollRef, preview, showReply, suggest }: {
  chatId: string | null;
  chat: (Chat & { runs: ChatRun[] }) | null;
  scrollRef: RefObject<HTMLDivElement | null>;
  preview: (document: DocumentRecord) => void;
  showReply: (id: string) => void;
  suggest: (text: string) => void;
}) {
  return <div className="chat-scroll" ref={scrollRef}>
    {!chatId ? <div className="welcome">
      <div className="hero">
        <div className="hero-copy"><span className="hero-icon"><ThunderboltFilled /></span>
          <h1>今天想完成什么？</h1><p>和 AI 一起处理文档、编写代码，<br className="compact-break" />把日常工作自动化。</p></div>
        <div className="hero-orbit" aria-hidden="true"><i /><i /><i /><i /><b /><span>✦</span><em /></div>
      </div>
      <div className="suggestion-head"><ReadOutlined /> 快速开始</div>
      <div className="suggestions">{suggestions.map(([label, note, Icon, text]) => <button key={label} onClick={() => suggest(text)}>
        <span className="suggestion-icon"><Icon /></span><strong>{label}</strong><small>{note}</small><ArrowRightOutlined className="suggestion-arrow" />
      </button>)}</div>
    </div> : !chat ? <div className="center"><Spin /></div> : <div className="transcript">
      <div className="chat-title"><h1>{chat.title}</h1><Tag>{Sources[chat.source] ?? chat.source}</Tag></div>
      {chat.runs.map((row) => <article className="turn" key={row.id}>
        <div className="user-message"><div>{row.prompt}</div>
          {!!row.attachments.length && <DocumentTiles documents={row.attachments} preview={preview} />}
          <small>{date(row.created)}</small>
        </div>
        <div className="agent-message"><span className="agent-mark"><ThunderboltFilled /></span><div className="answer">
          <div className="answer-head"><b>AI 助手</b><Status value={row.state} /></div>
          <RunProcess run={row} />
          {row.answer && <Markdown remarkPlugins={[remarkGfm]} components={markdownTableCells}>{row.answer}</Markdown>}
          {!activeRunStates.includes(row.state) && !!row.documents.length && <section className="turn-documents" aria-label="本轮生成的文件">
            <b>本轮生成的文件</b><DocumentTiles documents={row.documents} preview={preview} />
          </section>}
          {row.error && <Alert type={row.state === 'canceled' ? 'info' : 'error'} title={row.error} showIcon />}
          {row.reply !== 'none' && chat.bot && <div className="reply-label">消息回传 <Status value={row.reply} />
            <Button type="link" size="small" onClick={() => showReply(row.id)}>查看回复记录</Button></div>}
          {row.reply === 'accepted' && row.reply_text && row.reply_text !== row.answer &&
            <div className="reply-text"><b>实际发送</b><p>{row.reply_text}</p></div>}
        </div></div>
      </article>)}
    </div>}
  </div>;
}

export function ChatComposer({
  chat, chatId, settings, attachments, removeAttachment, uploadStarted, uploadFinished, inputRef, prompt, setPrompt,
  options, selected, setSelected, loading, uploading, send, stop,
}: {
  chat: Chat | null;
  chatId: string | null;
  settings: { ready: boolean; documentMaxBytes: number } | null;
  attachments: DocumentRecord[];
  removeAttachment: (id: string) => void;
  uploadStarted: () => void;
  uploadFinished: (document?: DocumentRecord) => void;
  inputRef: RefObject<ComponentRef<typeof Input.TextArea> | null>;
  prompt: string;
  setPrompt: (prompt: string) => void;
  options: SkillOption[];
  selected: string[];
  setSelected: (skills: string[]) => void;
  loading: boolean;
  uploading: boolean;
  send: () => void;
  stop?: () => void;
}) {
  return <div className="composer-area">
    {settings && !settings.ready && <Alert className="model-alert" type="warning" showIcon title="模型密钥尚未配置" />}
    {chat?.archived || chat?.bot ? <p className="readonly">{chat.archived ? '此会话已归档' : '机器人会话由已连接的平台接收消息'}</p> : <div className="composer">
      {!!attachments.length && <div className="composer-attachments">{attachments.map((document) => (
        <Tag key={document.id} closable onClose={() => removeAttachment(document.id)}><OverflowText text={document.name} /></Tag>
      ))}</div>}
      <PreviewTextArea ref={inputRef} aria-label="需求" placeholder="输入你的需求…" value={prompt} maxLength={chatLimits.prompt}
        autoSize={chatViewConfig.composerRows} variant="borderless" onChange={(event) => setPrompt(event.target.value)}
        onPressEnter={(event) => { if (!event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send(); } }} />
      <div className="composer-actions">
        <Select aria-label="引用 Skill" className="composer-skills" mode="multiple" options={options} value={selected} onChange={setSelected}
          {...skillLabelPreviews}
          maxCount={chatLimits.skills} maxTagCount={chatViewConfig.visibleSkills} popupMatchSelectWidth={chatViewConfig.skillPopupWidth} placeholder={<><ToolOutlined /> 引用 Skill</>} notFoundContent="请先在 Skill 管理中上传" />
        <div className="send-actions">
          <DocumentUpload compact chat={chatId ?? undefined} maxBytes={settings?.documentMaxBytes} disabled={loading}
            onStart={uploadStarted} onFinish={uploadFinished} />
          {stop && <Button aria-label="停止生成" icon={<StopOutlined />} onClick={stop} />}
          <Button aria-label="发送" className="send" type="primary" icon={<ArrowUpOutlined />}
            disabled={uploading || (!prompt.trim() && !attachments.length)} loading={loading} onClick={send} />
        </div>
      </div>
    </div>}
    <div className="composer-footer"><span>由 Pi Durable 驱动</span><span>Shift + Enter 换行</span></div>
  </div>;
}
