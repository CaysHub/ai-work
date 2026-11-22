export type Platform = 'wecom' | 'feishu' | 'dingtalk';
export type ChatSource = Platform | 'web' | 'schedule';
export type BotMode = 'bot' | 'personal';
export type UserRole = 'admin' | 'member';
export type RunState = 'queued' | 'running' | 'done' | 'failed' | 'canceled';
export type ReplyState = 'none' | 'waiting' | 'awaiting_review' | 'pending' | 'sending' | 'accepted' | 'unknown' | 'failed' | 'canceled' | 'skipped';
export type ReviewState = 'queued' | 'notifying' | 'ready' | 'failed' | 'unknown' | 'approved' | 'rejected' | 'expired' | 'commanded' | 'canceled';

export interface User {
  id: string;
  username: string;
  name: string;
  role: UserRole;
}

export interface Chat {
  id: string;
  owner: string;
  title: string;
  source: ChatSource;
  bot: string | null;
  target: string | null;
  version: number | null;
  archived: number;
  created: number;
  updated: number;
}

export interface ToolEvent {
  id: string;
  name: string;
  state: 'running' | 'done' | 'failed';
  started?: number;
  input?: string;
  output?: string;
}

export interface RunProgress {
  id: string;
  at: number;
  title: string;
  summary: string;
}

export interface ChatRun {
  id: string;
  prompt: string;
  skills: string[];
  state: RunState;
  reply: ReplyState;
  answer: string | null;
  error: string | null;
  created: number;
  updated: number;
  reply_text: string | null;
  sent_at: number | null;
  tool_log: ToolEvent[];
  progress_log: RunProgress[];
  attachments: DocumentRecord[];
  documents: DocumentRecord[];
}

export type DocumentSource = 'upload' | 'generated' | 'workspace';
export interface DocumentRecord {
  id: string;
  name: string;
  path: string | null;
  bytes: number;
  source: DocumentSource;
  chat: string | null;
  chat_title: string | null;
  run_id: string | null;
  created: number;
}

export type DocumentPreview =
  | { kind: 'text' | 'markdown'; text: string; truncated: boolean }
  | { kind: 'image' | 'pdf' | 'download' };

export interface Skill {
  id: string;
  name: string;
  description: string;
  created: number;
}

export interface Schedule {
  id: string;
  name: string;
  prompt: string;
  skills: string[];
  cron: string;
  tz: string;
  enabled: number;
  next: number | null;
  last_state: RunState | null;
  last_chat: string | null;
}

export interface BotSettings {
  skills: string[];
  employeeId: string;
  targets: { id: string; group: boolean }[];
  discoverGroups?: boolean;
  reviewConversation?: string;
}

export interface WecomMcpUrls {
  messageMcp?: string;
  documentMcp?: string;
}

export const botCredentialKeys = ['appId', 'appSecret', 'botId', 'botSecret', 'messageMcp', 'documentMcp', 'userToken', 'folderToken', 'dwsProfile'] as const;
export type BotCredentialKey = typeof botCredentialKeys[number];
export type BotCredentials = Partial<Record<BotCredentialKey, string>>;

export interface Bot {
  id: string;
  owner: string;
  name: string;
  platform: Platform;
  mode: BotMode;
  settings: BotSettings;
  enabled: number;
  version: number;
  since: number;
  status: 'offline' | 'connecting' | 'online' | 'error';
  detail: string | null;
  updated: number;
  configured: string[];
}

export interface BotConfiguration extends Bot {
  secrets: BotCredentials;
}

export interface BotActivity {
  id: string;
  chat: string;
  state: RunState;
  reply: ReplyState;
  receipt: string | null;
  created: number;
  target: string;
}

export interface ReplyRecord {
  id: string;
  chat: string;
  prompt: string;
  answer: string | null;
  reply: ReplyState;
  error: string | null;
  can_cancel: 0 | 1;
  sent_parts: number;
  receipt: string | null;
  reply_text: string | null;
  sent_at: number | null;
  created: number;
  title: string;
  source: ChatSource;
  bot: string;
  target: string;
  version: number;
  owner: string;
  review_state: ReviewState | null;
  draft: string | null;
  notified: number | null;
  decided: number | null;
  document?: { title: string; content: string; url: string | null };
}

export interface ReplyPage {
  rows: ReplyRecord[];
  total: number;
  bots: Pick<Bot, 'id' | 'name' | 'platform'>[];
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
