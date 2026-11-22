import React, { useEffect, useRef, useState } from 'react';
import { Alert, App as AntApp, Avatar, Button, ConfigProvider, Dropdown, Form, Input, Modal, Select, Spin, Tag } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import {
  ApiOutlined, ArrowRightOutlined, ArrowUpOutlined, ClockCircleOutlined, CodeOutlined, EllipsisOutlined, FolderOpenOutlined,
  FormOutlined, HistoryOutlined, LogoutOutlined, MenuOutlined, MessageOutlined, PlusOutlined, ReadOutlined,
  ScheduleOutlined, SearchOutlined, StopOutlined, TeamOutlined, ThunderboltFilled, ToolOutlined, UserOutlined,
} from '@ant-design/icons';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { markdownTableCells, OverflowText, skillLabelPreviews } from '../compact-table.tsx';
import { PreviewInput, PreviewTextArea } from '../content-preview.tsx';
import { api, date, Sources, Status } from '../shared.tsx';
import { Bots, Schedules, Skills, Users } from '../views.tsx';
import { Replies } from '../replies.tsx';
import { RunProcess } from '../run-process.tsx';
import { DocumentDrawer, Documents, DocumentTiles, DocumentUpload } from '../documents.tsx';
import 'antd/dist/reset.css';
import '../style.css';
import type { Chat, ChatRun, DocumentRecord, Skill, User } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';

type Conversation = Chat & { runs: ChatRun[] };
type AuthStatus = { user: User | null; setup: boolean };
type Settings = { model: string; ready: boolean; documentMaxBytes: number };
type Credentials = { username: string; password: string; name?: string };
const nav = [
  ['chat', 'AI 助手', MessageOutlined], ['skills', 'Skill 管理', ToolOutlined],
  ['schedules', '定时任务', ClockCircleOutlined], ['bots', '机器人接入', ApiOutlined], ['replies', '回复记录', HistoryOutlined],
  ['documents', '文档管理', FolderOpenOutlined],
] as const;
const adminPage = ['users', '团队成员', TeamOutlined] as const;
type Page = typeof nav[number][0] | typeof adminPage[0];
const suggestions = [
  ['整理会议纪要', '提炼决策、待办和负责人', FormOutlined, '请帮我将下面的会议记录整理为会议纪要，提取关键决策、待办、负责人和截止时间。\n\n'],
  ['编写代码', '生成代码并保存到工作区', CodeOutlined, '请帮我编写代码，并将相关文件保存到当前会话工作区。我的需求是：'],
  ['撰写工作周报', '汇总进展、风险和下周计划', ScheduleOutlined, '请根据下面的工作记录撰写周报，包含本周进展、风险和下周计划。\n\n'],
] as const;

function Brand() {
  return <div className="brand"><span className="brand-mark"><ThunderboltFilled /></span><div><strong>AI 工作台</strong><small>企业工作空间</small></div></div>;
}
function Account({ setup, login }: { setup: boolean; login: (user: User) => void }) {
  const { message } = AntApp.useApp();
  const [busy, setBusy] = useState(false);
  async function submit(values: Credentials) {
    setBusy(true);
    try { login(await api<User>('/auth/' + (setup ? 'setup' : 'login'), 'POST', values)); }
    catch (error) { message.error(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <div className="login-layout">
    <section className="login-brand"><Brand /><div><h1>把日常工作<br />交给 AI 助手</h1><p>会话、Skill、定时任务和机器人集中管理。</p><div className="login-orbit"><i /><i /><i /></div></div></section>
    <section className="login-main"><div className="login-card">
      <h2>{setup ? '初始化工作台' : '登录工作台'}</h2>
      <p>{setup ? '创建管理员账户，随后可邀请团队成员。' : '使用你的工作台账号登录。'}</p>
      <Form layout="vertical" onFinish={submit} requiredMark={false}>
        <Form.Item name="username" label="用户名" rules={[{ required: true, pattern: /^[a-zA-Z0-9_.-]{3,40}$/, message: '输入 3 至 40 位字母、数字或 . _ -' }]}>
          <PreviewInput autoComplete="username" prefix={<UserOutlined />} />
        </Form.Item>
        {setup && <Form.Item name="name" label="显示名称" rules={[{ required: true, max: 40 }]}><PreviewInput /></Form.Item>}
        <Form.Item name="password" label="密码" rules={[{ required: true, min: 10, message: '密码至少 10 位' }]}>
          <Input.Password autoComplete={setup ? 'new-password' : 'current-password'} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} block>{setup ? '创建管理员' : '登录'} <ArrowRightOutlined /></Button>
      </Form>
    </div></section>
  </div>;
}

function Workspace({ user, logout }: { user: User; logout: () => Promise<void> }) {
  const { message } = AntApp.useApp();
  const [reviewId, setReviewId] = useState(() => new URLSearchParams(location.search).get('review'));
  const [page, setPage] = useState<Page>(() => reviewId ? 'replies' : 'chat');
  const [chatId, setChatId] = useState<string | null>(null);
  const [chats, setChats] = useState<Chat[]>([]);
  const [chat, setChat] = useState<Conversation | null>(null);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [prompt, setPrompt] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [archive, setArchive] = useState(false);
  const [search, setSearch] = useState('');
  const [documentScope, setDocumentScope] = useState<Chat | null>(null);
  const [documentPreview, setDocumentPreview] = useState<DocumentRecord | null>(null);
  const [attachments, setAttachments] = useState<DocumentRecord[]>([]);
  const [uploading, setUploading] = useState(0);
  const draftRevision = useRef(0);
  const revision = draftRevision.current;
  const [sidebar, setSidebar] = useState(false);
  const [rename, setRename] = useState<string | null>(null);
  const messages = useRef<HTMLDivElement>(null);
  const input = useRef<React.ComponentRef<typeof Input.TextArea>>(null);
  const refreshChats = () => api<Chat[]>('/chats?archived=' + archive).then(setChats);
  const refreshSkills = () => api<Skill[]>('/skills').then(setSkills);
  const reportError = (error: unknown) => message.error(errorMessage(error));
  useEffect(() => { refreshSkills().catch(reportError); api<Settings>('/settings').then(setSettings).catch(reportError); }, []);
  useEffect(() => {
    let valid = true;
    const refresh = () => api<Chat[]>('/chats?archived=' + archive).then((rows) => { if (valid) setChats(rows); }).catch(() => {});
    refresh();
    const timer = setInterval(refresh, 3500);
    return () => { valid = false; clearInterval(timer); };
  }, [archive]);
  useEffect(() => {
    setChat(null);
    if (!chatId) return;
    let valid = true;
    let first = true;
    const refresh = async () => {
      try {
        const value = await api<Conversation>('/chats/' + chatId);
        if (valid) setChat(value);
      } catch (error) { if (valid && first) reportError(error); }
      finally { first = false; }
    };
    refresh();
    const timer = setInterval(refresh, 1300);
    return () => { valid = false; clearInterval(timer); };
  }, [chatId]);
  const last = chat?.runs.at(-1);
  useEffect(() => { messages.current?.scrollTo({ top: messages.current.scrollHeight }); }, [chatId, last?.id, last?.state]);
  function openChat(id: string | null) {
    draftRevision.current += 1;
    setChatId(id);
    setPage('chat');
    setSidebar(false);
    setPrompt('');
    setSelected([]);
    setAttachments([]);
    setDocumentPreview(null);
  }
  async function send() {
    if ((!prompt.trim() && !attachments.length) || loading || uploading) return;
    setLoading(true);
    try {
      const id = chatId ?? (await api<Chat>('/chats', 'POST', {})).id;
      if (!chatId && revision === draftRevision.current) setChatId(id);
      await api('/chats/' + id + '/runs', 'POST', {
        prompt: prompt.trim() || '请阅读本轮附件并概述要点。', skills: selected,
        documents: attachments.map(({ id }) => id), requestId: crypto.randomUUID?.(),
      });
      const result = await api<Conversation>('/chats/' + id);
      if (revision === draftRevision.current) {
        setChat(result);
        setPrompt('');
        setAttachments([]);
      }
      await refreshChats();
    } catch (error) { reportError(error); }
    finally { setLoading(false); }
  }
  function showReply(id: string) {
    setReviewId(id);
    setPage('replies');
  }
  function dismissReply() {
    setReviewId(null);
    const url = new URL(location.href);
    if (!url.searchParams.has('review')) return;
    url.searchParams.delete('review');
    history.replaceState(null, '', url);
  }
  async function archiveChat() {
    if (!chat) return;
    await api('/chats/' + chatId, 'PATCH', { archived: !chat.archived });
    openChat(null);
    await refreshChats();
  }
  const activeTitle = nav.find(([name]) => name === page)?.[1] ?? '团队成员';
  const displayChats = chats.filter((row) => row.title.toLowerCase().includes(search.toLowerCase()));
  const options = skills.map((skill) => ({ value: skill.id, label: skill.name, title: '' }));
  const busy = last && ['queued', 'running'].includes(last.state);
  return <div className="layout">
    {sidebar && <button className="overlay" onClick={() => setSidebar(false)} aria-label="收起侧栏" />}
    <aside className={'sidebar ' + (sidebar ? 'sidebar-open' : '')}>
      <Brand />
      <button className="new-chat" onClick={() => openChat(null)}><PlusOutlined /> 新会话 <FormOutlined className="new-end" /></button>
      <div className="section-label">工作空间</div>
      <nav>{[...nav, ...(user.role === 'admin' ? [adminPage] : [])].map(([key, label, Icon]) =>
        <button key={key} className={'nav-item ' + (page === key ? 'active' : '')} onClick={() => {
          if (key === 'documents') setDocumentScope(null);
          setPage(key);
          setSidebar(false);
        }}>
          <Icon /> {label}
        </button>)}</nav>
      <div className="recents">
        <div className="history-head"><span>会话历史</span><button aria-label={archive ? '查看当前会话' : '查看归档会话'} title={archive ? '当前会话' : '归档会话'} onClick={() => setArchive(!archive)}><HistoryOutlined /></button></div>
        <Input prefix={<SearchOutlined />} placeholder={archive ? '搜索归档会话' : '搜索历史会话'} value={search} onChange={(e) => setSearch(e.target.value)} />
        <div className="history-rows">
          {displayChats.map((row) => <button key={row.id} className={'history-row ' + (row.id === chatId ? 'selected' : '')} onClick={() => openChat(row.id)}>
            <MessageOutlined /><OverflowText text={row.title} popupClassName="sidebar-content-preview" />
          </button>)}
          {!displayChats.length && <span className="history-empty">{archive ? '暂无归档会话' : search ? '没有匹配的会话' : '暂无历史会话'}</span>}
        </div>
      </div>
      <div className="profile"><Avatar>{user.name[0]}</Avatar><div><b>{user.name}</b><small>{user.role === 'admin' ? '工作台管理员' : '团队成员'}</small></div>
        <Button type="text" title="退出登录" aria-label="退出登录" icon={<LogoutOutlined />} onClick={logout} /></div>
    </aside>
    <main className="main">
      <header className="topbar">
        <Button className="mobile-menu" type="text" icon={<MenuOutlined />} aria-label="菜单" onClick={() => setSidebar(!sidebar)} />
        <div className="breadcrumb">工作空间 <span>/</span> <b>{activeTitle}</b></div>
        <div className="header-right">
          {page === 'chat' && chat && <><Button icon={<FolderOpenOutlined />} onClick={() => {
            setDocumentScope(chat);
            setPage('documents');
          }}>会话文件</Button>
            <Dropdown menu={{ items: [{ key: 'rename', label: '重命名' }, { key: 'archive', label: chat.archived ? '恢复会话' : '归档会话' }],
              onClick: ({ key }) => key === 'rename' ? setRename(chat.title) : archiveChat().catch(reportError) }}>
              <Button aria-label="会话操作" icon={<EllipsisOutlined />} />
            </Dropdown></>}
          <span className="model-pill"><i className={settings?.ready ? 'ready' : ''} /> {settings?.model ?? 'Pi Durable'}</span>
        </div>
      </header>
      {page === 'skills' && <Skills skills={skills} refresh={refreshSkills} />}
      {page === 'schedules' && <Schedules options={options} openChat={openChat} />}
      {page === 'bots' && <Bots options={options} openChat={openChat} />}
      {page === 'replies' && <Replies reviewId={reviewId} showReply={showReply} dismissReply={dismissReply} openChat={openChat} />}
      {page === 'documents' && <Documents key={documentScope?.id ?? 'all'} scope={documentScope}
        clearScope={() => setDocumentScope(null)} openChat={openChat} preview={setDocumentPreview} maxBytes={settings?.documentMaxBytes} />}
      {page === 'users' && <Users />}
      {page === 'chat' && <div className="chat-shell">
        <div className="chat-scroll" ref={messages}>
          {!chatId ? <div className="welcome">
            <div className="hero">
              <div className="hero-copy"><span className="hero-icon"><ThunderboltFilled /></span>
                <h1>今天想完成什么？</h1><p>和 AI 一起处理文档、编写代码，<br className="compact-break" />把日常工作自动化。</p></div>
              <div className="hero-orbit" aria-hidden="true"><i /><i /><i /><i /><b /><span>✦</span><em /></div>
            </div>
            <div className="suggestion-head"><ReadOutlined /> 快速开始</div>
            <div className="suggestions">{suggestions.map(([label, note, Icon, text]) => <button key={label} onClick={() => { setPrompt(text); input.current?.focus(); }}>
              <span className="suggestion-icon"><Icon /></span><strong>{label}</strong><small>{note}</small><ArrowRightOutlined className="suggestion-arrow" />
            </button>)}</div>
          </div> : !chat ? <div className="center"><Spin /></div> : <div className="transcript">
            <div className="chat-title"><h1>{chat.title}</h1><Tag>{Sources[chat.source] ?? chat.source}</Tag></div>
            {chat.runs.map((row) => <article className="turn" key={row.id}>
              <div className="user-message"><div>{row.prompt}</div>
                {!!row.attachments.length && <DocumentTiles documents={row.attachments} preview={setDocumentPreview} />}
                <small>{date(row.created)}</small>
              </div>
              <div className="agent-message"><span className="agent-mark"><ThunderboltFilled /></span><div className="answer">
                <div className="answer-head"><b>AI 助手</b><Status value={row.state} /></div>
                <RunProcess run={row} />
                {row.answer && <Markdown remarkPlugins={[remarkGfm]} components={markdownTableCells}>{row.answer}</Markdown>}
                {!['queued', 'running'].includes(row.state) && !!row.documents.length && <section className="turn-documents" aria-label="本轮生成的文件">
                  <b>本轮生成的文件</b><DocumentTiles documents={row.documents} preview={setDocumentPreview} />
                </section>}
                {row.error && <Alert type={row.state === 'canceled' ? 'info' : 'error'} title={row.error} showIcon />}
                {row.reply !== 'none' && chat.bot && <div className="reply-label">消息回传 <Status value={row.reply} />
                  <Button type="link" size="small" onClick={() => showReply(row.id)}>查看回复记录</Button></div>}
                {row.reply === 'accepted' && row.reply_text && row.reply_text !== row.answer &&
                  <div className="reply-text"><b>实际发送</b><p>{row.reply_text}</p></div>}
              </div></div>
            </article>)}
          </div>}
        </div>
        <div className="composer-area">
          {settings && !settings.ready && <Alert className="model-alert" type="warning" showIcon title="模型密钥尚未配置" />}
          {chat?.archived || chat?.bot ? <p className="readonly">{chat.archived ? '此会话已归档' : '机器人会话由已连接的平台接收消息'}</p> : <div className="composer">
            {!!attachments.length && <div className="composer-attachments">{attachments.map((document) => (
              <Tag key={document.id} closable onClose={() =>
                setAttachments((current) => current.filter((item) => item.id !== document.id))}><OverflowText text={document.name} /></Tag>
            ))}</div>}
            <PreviewTextArea ref={input} aria-label="需求" placeholder="输入你的需求…" value={prompt} maxLength={24000}
              autoSize={{ minRows: 2, maxRows: 7 }} variant="borderless" onChange={(event) => setPrompt(event.target.value)}
              onPressEnter={(event) => { if (!event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send(); } }} />
            <div className="composer-actions">
              <Select aria-label="引用 Skill" className="composer-skills" mode="multiple" options={options} value={selected} onChange={setSelected}
                {...skillLabelPreviews}
                maxCount={8} maxTagCount={2} popupMatchSelectWidth={240} placeholder={<><ToolOutlined /> 引用 Skill</>} notFoundContent="请先在 Skill 管理中上传" />
              <div className="send-actions">
                <DocumentUpload compact chat={chatId ?? undefined} maxBytes={settings?.documentMaxBytes} disabled={loading}
                  onStart={() => setUploading((count) => count + 1)}
                  onFinish={(document) => {
                    setUploading((count) => count - 1);
                    if (!document || revision !== draftRevision.current) return;
                    setAttachments((current) => [...current, document].slice(0, 10));
                  }} />
                {busy && <Button aria-label="停止生成" icon={<StopOutlined />} onClick={() => api('/chats/' + chatId + '/runs/' + last.id + '/cancel', 'POST').catch(reportError)} />}
                <Button aria-label="发送" className="send" type="primary" icon={<ArrowUpOutlined />}
                  disabled={!!uploading || (!prompt.trim() && !attachments.length)} loading={loading} onClick={send} />
              </div>
            </div>
          </div>}
          <div className="composer-footer"><span>由 Pi Durable 驱动</span><span>Shift + Enter 换行</span></div>
        </div>
      </div>}
      {documentPreview && <DocumentDrawer key={documentPreview.id} document={documentPreview} close={() => setDocumentPreview(null)} />}
      <Modal title="重命名会话" open={rename !== null} onCancel={() => setRename(null)} onOk={async () => {
        if (!chat || rename === null) return;
        try { const updated = await api<Chat>('/chats/' + chatId, 'PATCH', { title: rename }); setChat({ ...chat, title: updated.title }); setRename(null); await refreshChats(); }
        catch (error) { reportError(error); }
      }}><PreviewInput value={rename ?? ''} maxLength={160} onChange={(event) => setRename(event.target.value)} /></Modal>
    </main>
  </div>;
}
function Root() {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { api<AuthStatus>('/auth/status').then(setStatus).catch((failure: unknown) => setError(errorMessage(failure))); }, []);
  if (error) return <div className="center"><Alert type="error" showIcon title={error} /></div>;
  if (!status) return <div className="center"><Spin size="large" /></div>;
  if (!status.user) return <Account setup={status.setup} login={(user) => setStatus({ user, setup: false })} />;
  return <Workspace user={status.user} logout={async () => { await api('/auth/logout', 'POST'); setStatus({ user: null, setup: false }); }} />;
}
const theme = { token: { colorPrimary: '#186c51', borderRadius: 10, fontFamily: 'Inter, Geist, "Microsoft YaHei", sans-serif' } };
export default function WorkbenchPage() {
  return <ConfigProvider locale={zhCN} theme={theme}><AntApp><Root /></AntApp></ConfigProvider>;
}
