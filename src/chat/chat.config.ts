import { CodeOutlined, FormOutlined, ScheduleOutlined } from '@ant-design/icons';

export const suggestions = [
  ['整理会议纪要', '提炼决策、待办和负责人', FormOutlined, '请帮我将下面的会议记录整理为会议纪要，提取关键决策、待办、负责人和截止时间。\n\n'],
  ['编写代码', '生成代码并保存到工作区', CodeOutlined, '请帮我编写代码，并将相关文件保存到当前会话工作区。我的需求是：'],
  ['撰写工作周报', '汇总进展、风险和下周计划', ScheduleOutlined, '请根据下面的工作记录撰写周报，包含本周进展、风险和下周计划。\n\n'],
] as const;

export const chatViewConfig = {
  historyRefreshMs: 3_500,
  conversationRefreshMs: 1_300,
  composerRows: { minRows: 2, maxRows: 7 },
  visibleSkills: 2,
  skillPopupWidth: 240,
  attachmentPrompt: '请阅读本轮附件并概述要点。',
};
export const activeRunStates = ['queued', 'running'];

export const toolNames: Record<string, string> = {
  workspace_write: '保存文件', workspace_read: '读取文件', workspace_list: '查看工作区',
  word_document_create: '生成 Word 文档', attached_document_read: '读取附件',
  skill_read: '读取 Skill 资源', cloud_document: '操作云文档', cloud_document_search: '搜索云文档',
  wecom_document_search: '搜索企微文档', wecom_sheet_read: '读取企微表格',
  reply_document: '准备回复文档', wecom_reply_decision: '提交企微回复决定', mcp_tools: '调用 MCP 工具',
};
