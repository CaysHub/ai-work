import type { ChatSource } from '../../shared/types.ts';

export const Sources: Record<ChatSource, string> = { web: '网页会话', schedule: '定时任务', wecom: '企业微信', feishu: '飞书', dingtalk: '钉钉' };
export const statuses: Record<string, [string, string?]> = {
  queued: ['排队中', 'gold'], running: ['处理中', 'processing'], done: ['已完成', 'success'], failed: ['失败', 'error'],
  canceled: ['已取消'], waiting: ['等待结果'], pending: ['等待发送', 'processing'], sending: ['发送中', 'processing'],
  accepted: ['平台已受理', 'success'], unknown: ['结果待核对', 'warning'], offline: ['已停用'],
  connecting: ['连接中', 'processing'], online: ['已连接', 'success'], error: ['连接异常', 'error'],
  awaiting_review: ['待本人指令', 'gold'], rejected: ['已忽略'], expired: ['已失效'], commanded: ['已接管'],
  skipped: ['无需处理'],
};
