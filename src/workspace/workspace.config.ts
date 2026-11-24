import {
  ApiOutlined, ClockCircleOutlined, FolderOpenOutlined, HistoryOutlined, MessageOutlined, TeamOutlined, ToolOutlined,
} from '@ant-design/icons';

export const nav = [
  ['chat', 'AI 助手', MessageOutlined], ['skills', 'Skill 管理', ToolOutlined],
  ['schedules', '定时任务', ClockCircleOutlined], ['bots', '机器人接入', ApiOutlined], ['replies', '回复记录', HistoryOutlined],
  ['documents', '文档管理', FolderOpenOutlined],
] as const;
export const adminPage = ['users', '团队成员', TeamOutlined] as const;
export type Page = typeof nav[number][0] | typeof adminPage[0];
