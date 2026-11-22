import React from 'react';
import { Tag } from 'antd';
import type { ChatSource } from '../shared/types.ts';

export type SkillOption = { value: string; label: string; title: string };

export async function api<T = unknown>(path: string, method = 'GET', body?: FormData | object): Promise<T> {
  const form = body instanceof FormData;
  const res = await fetch('/api' + path, { method,
    headers: body == null || form ? {} : { 'Content-Type': 'application/json' },
    body: form ? body : body == null ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return null as T;
  const data = await res.json().catch(() => ({ error: '工作台服务不可用' }));
  if (!res.ok) throw new Error(data.error ?? '请求失败');
  return data;
}
export const date = (value: number | null | undefined) => value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' }).format(value) : '—';
export const Sources: Record<ChatSource, string> = { web: '网页会话', schedule: '定时任务', wecom: '企业微信', feishu: '飞书', dingtalk: '钉钉' };
const statuses: Record<string, [string, string?]> = {
  queued: ['排队中', 'gold'], running: ['处理中', 'processing'], done: ['已完成', 'success'], failed: ['失败', 'error'],
  canceled: ['已取消'], waiting: ['等待结果'], pending: ['等待发送', 'processing'], sending: ['发送中', 'processing'],
  accepted: ['平台已受理', 'success'], unknown: ['结果待核对', 'warning'], offline: ['已停用'],
  connecting: ['连接中', 'processing'], online: ['已连接', 'success'], error: ['连接异常', 'error'],
  awaiting_review: ['待本人指令', 'gold'], rejected: ['已忽略'], expired: ['已失效'], commanded: ['已接管'],
  skipped: ['无需处理'],
};
export function Status({ value }: { value?: string | null }) {
  const [label, color] = statuses[value ?? ''] ?? ['—'];
  return <Tag color={color}>{label}</Tag>;
}
