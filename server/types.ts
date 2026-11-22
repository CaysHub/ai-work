import type { Request, Response } from 'express';
import type { Bot, Chat, ChatRun, ReviewState, Schedule, Skill, User } from '../shared/types.ts';

declare global {
  namespace Express {
    interface Request {
      user?: User;
    }
  }
}

export type SaveRequest = Request<{ id?: string }>;
export type { Response };

export interface Secrets {
  appId?: string;
  appSecret?: string;
  botId?: string;
  botSecret?: string;
  messageMcp?: string;
  documentMcp?: string;
  userToken?: string;
  folderToken?: string;
  dwsProfile?: string;
  refreshToken?: string;
  expiresAt?: number;
  refreshing?: boolean;
  oauthClientId?: string;
  corpId?: string;
}

export interface StoredTables {
  chats: Chat;
  skills: Skill & { owner: string; entry: string; archived: number };
  schedules: Omit<Schedule, 'skills' | 'last_state' | 'last_chat'> & { owner: string; skills: string; created: number };
  bots: Omit<Bot, 'settings' | 'configured'> & { settings: string; secrets: string };
}

export type LiveBot = Omit<Bot, 'configured'> & { secrets: Secrets };
export type RunRow = Omit<ChatRun, 'skills' | 'tool_log' | 'progress_log' | 'attachments' | 'documents'> & {
  chat: string;
  skills: string;
  tool_log: string;
  progress_log: string;
  transport: string | null;
  submitted: number;
};
export type AgentRun = RunRow & Pick<Chat, 'owner' | 'bot' | 'version' | 'target' | 'source'>;
export type BotRun = RunRow & { bot: string; target: string; version: number; title: string };

export type Transport =
  | { channel: 'personal'; sourceTime: number; sourceId: string }
  | { channel: 'dingtalk'; group: boolean }
  | { channel: 'wecom' | 'feishu' };

export interface InboundMessage {
  id?: string;
  text?: string;
  sender?: string;
}

export type ReviewSender = (text: string, id: string) => Promise<string | null>;

export interface ReplyReview {
  id: string;
  state: ReviewState;
  draft: string;
  created: number;
  notified: number | null;
  decided: number | null;
}
