import {
EllipsisOutlined,FolderOpenOutlined,FormOutlined,HistoryOutlined,LogoutOutlined,MenuOutlined,
MessageOutlined,PlusOutlined,SearchOutlined,
} from '@ant-design/icons';
import { App as AntApp, Avatar, Button, Dropdown, Input, Modal } from 'antd';
import type { ComponentRef } from 'react';
import { useEffect, useRef, useState } from 'react';
import { chatLimits } from '../../shared/config.ts';
import type { Chat, ChatRun, DocumentRecord, Skill, User } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { Bots } from '../bots/Bots.tsx';
import { activeRunStates, chatViewConfig } from '../chat/chat.config.ts';
import { ChatComposer, ChatTranscript } from '../chat/Chat.tsx';
import { api } from '../common/api.ts';
import { OverflowText } from '../common/CompactTable.tsx';
import { PreviewInput } from '../common/ContentPreview.tsx';
import { DocumentDrawer, Documents } from '../documents/Documents.tsx';
import { Replies } from '../replies/Replies.tsx';
import { Schedules } from '../schedules/Schedules.tsx';
import { Skills } from '../skills/Skills.tsx';
import { Users } from '../users/Users.tsx';
import { Brand } from './Brand.tsx';
import type { Page } from './workspace.config.ts';
import { adminPage, nav } from './workspace.config.ts';

type Conversation = Chat & { runs: ChatRun[] };
type Settings = { model: string; ready: boolean; documentMaxBytes: number };

export function Workspace({ user, logout }: { user: User; logout: () => Promise<void> }) {
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
  const input = useRef<ComponentRef<typeof Input.TextArea>>(null);
  const refreshChats = () => api<Chat[]>('/chats?archived=' + archive).then(setChats);
  const refreshSkills = () => api<Skill[]>('/skills').then(setSkills);
  const reportError = (error: unknown) => message.error(errorMessage(error));
  useEffect(() => { refreshSkills().catch(reportError); api<Settings>('/settings').then(setSettings).catch(reportError); }, []);
  useEffect(() => {
    let valid = true;
    const refresh = () => api<Chat[]>('/chats?archived=' + archive).then((rows) => { if (valid) setChats(rows); }).catch(() => {});
    refresh();
    const timer = setInterval(refresh, chatViewConfig.historyRefreshMs);
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
    const timer = setInterval(refresh, chatViewConfig.conversationRefreshMs);
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
        prompt: prompt.trim() || chatViewConfig.attachmentPrompt, skills: selected,
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
  const busy = last && activeRunStates.includes(last.state);
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
        <ChatTranscript chatId={chatId} chat={chat} scrollRef={messages} preview={setDocumentPreview}
          showReply={showReply} suggest={(text) => { setPrompt(text); input.current?.focus(); }} />
        <ChatComposer chat={chat} chatId={chatId} settings={settings} attachments={attachments}
          removeAttachment={(id) => setAttachments((current) => current.filter((item) => item.id !== id))}
          uploadStarted={() => setUploading((count) => count + 1)}
          uploadFinished={(document) => {
            setUploading((count) => count - 1);
            if (!document || revision !== draftRevision.current) return;
            setAttachments((current) => [...current, document].slice(0, chatLimits.attachments));
          }}
          inputRef={input} prompt={prompt} setPrompt={setPrompt} options={options} selected={selected}
          setSelected={setSelected} loading={loading} uploading={!!uploading} send={send}
          stop={busy ? () => api('/chats/' + chatId + '/runs/' + last.id + '/cancel', 'POST').catch(reportError) : undefined} />
      </div>}
      {documentPreview && <DocumentDrawer key={documentPreview.id} document={documentPreview} close={() => setDocumentPreview(null)} />}
      <Modal title="重命名会话" open={rename !== null} onCancel={() => setRename(null)} onOk={async () => {
        if (!chat || rename === null) return;
        try { const updated = await api<Chat>('/chats/' + chatId, 'PATCH', { title: rename }); setChat({ ...chat, title: updated.title }); setRename(null); await refreshChats(); }
        catch (error) { reportError(error); }
      }}><PreviewInput value={rename ?? ''} maxLength={chatLimits.title} onChange={(event) => setRename(event.target.value)} /></Modal>
    </main>
  </div>;
}
