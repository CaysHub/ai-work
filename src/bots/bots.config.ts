import type { BotCredentialKey, Platform } from '../../shared/types.ts';

export const platforms: { value: Platform; label: string; short: string; note: string }[] = [
  { value: 'wecom', label: '企业微信', short: '企', note: '智能机器人长连接与员工消息 MCP' },
  { value: 'feishu', label: '飞书', short: '飞', note: '应用机器人、本人会话与云文档' },
  { value: 'dingtalk', label: '钉钉', short: '钉', note: 'Stream 机器人、本人会话与 DWS 文档' },
];

export const credentialLabels: Record<BotCredentialKey, string> = {
  appId: 'App ID', appSecret: 'App Secret', botId: 'Bot ID', botSecret: 'Bot Secret',
  messageMcp: '消息 MCP 授权地址', documentMcp: '文档 MCP 授权地址', userToken: '员工访问令牌',
  folderToken: '云空间文件夹 Token', dwsProfile: 'DWS CLI profile',
};

export const botEditorConfig = {
  width: 760,
  targetRows: 2,
  credentialRows: 1,
  mcpCredentialRows: 3,
  credentialAutoSize: { minRows: 1, maxRows: 4 },
  platform: 'wecom',
  mode: 'bot',
  discoverGroups: true,
  modeOptions: [{ value: 'bot', label: '应用机器人' }, { value: 'personal', label: '本人会话接管' }],
  mcpHelp: {
    messageMcp: '填写企业微信消息 MCP 授权链接，路径为 /mcp/v2/bot/msg。',
    documentMcp: '可选。使用在线文档时，填写企业微信文档 MCP 授权链接，路径为 /mcp/v2/bot/doc。',
  },
} as const;
