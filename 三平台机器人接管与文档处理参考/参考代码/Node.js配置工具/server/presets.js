export const capabilities = {
  doc: '文档', msg: '消息', todo: '待办', schedule: '日程',
  meeting: '会议', disk: '微盘', contact: '通讯录',
};

export const presets = [
  { id: 'dingtalk', name: '钉钉机器人', platform: 'DINGTALK', appId: 'YOUR_DINGTALK_APP_ID' },
  { id: 'feishu-primary', name: '飞书机器人', platform: 'FEISHU', appId: 'YOUR_FEISHU_APP_ID' },
  { id: 'wecom-primary', name: '企业微信机器人', platform: 'WECOM', appId: 'YOUR_WECOM_BOT_ID' },
];

export const preloaded = JSON.parse(process.env.BOT_CONFIG_BOOTSTRAP || '{}');
delete process.env.BOT_CONFIG_BOOTSTRAP;
