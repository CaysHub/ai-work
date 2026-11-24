import { httpPaths } from '../../shared/config.ts';

export const platformEndpoints = {
  feishuApi: 'https://open.feishu.cn/open-apis',
  feishuAuth: 'https://accounts.feishu.cn',
  feishuCallback: httpPaths.integrations + '/feishu/callback',
  dingApi: 'https://api.dingtalk.com/v1.0',
  dingAuth: 'https://mcp.dingtalk.com',
  dingLogin: 'https://login.dingtalk.com',
  dingMcp: 'https://mcp-gw.dingtalk.com/server/',
  dingDoc: 'https://alidocs.dingtalk.com/i/nodes/',
  wecomApi: 'https://qyapi.weixin.qq.com',
  wecomMcpPath: '/mcp/v2/bot/',
};
export const platformConfig = {
  httpTimeoutMs: 20_000, mcpTimeoutMs: 25_000, historyPages: 12, groupPages: 30, historyPageSize: 50,
  wecomTextMaxBytes: 2_048, dingReceiptAttempts: 3, dingReceiptRetryMs: 1_000,
  wecomTimeZone: 'Asia/Shanghai', wecomUtcOffset: '+08:00',
  mcpClient: { name: 'ai-workbench', version: '0.1.0' },
};

export const dingServices = {
  get_current_user_profile: 'db4b26cb38ea6a8739ad55d1997fa1da608cd36b33a6cf0f77884f70c49382fe',
  list_conversation_message_v2: '0a1609437385696b77fc4771c3ddaf5656b487f809966c0cc8d4755e7b1d3b74',
  send_personal_message: '0a1609437385696b77fc4771c3ddaf5656b487f809966c0cc8d4755e7b1d3b74',
  query_message_send_status: '450eede6b54d83e030140e66ec77c98a2e89a0869ef4db481f8217a98a42f821',
};
