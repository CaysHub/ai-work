export const accountLimits = {
  usernamePattern: /^[a-zA-Z0-9_.-]{3,40}$/,
  displayName: 40,
  passwordMin: 10,
  passwordMax: 128,
};

export const chatLimits = { title: 160, prompt: 24_000, skills: 8, attachments: 10 };
export const chatDefaults = { title: '新会话' };
export const botLimits = { name: 100, groupMentionName: 64 };
export const scheduleDefaults = { cron: '0 9 * * 1-5', timezone: 'Asia/Shanghai', enabled: true };
export const scheduleLimits = { name: 120 };
export const documentList = { pageSize: 20, queryLength: 200 };
export const replyList = { pageSize: 20, queryLength: 200 };
export const replyReferenceLength = 8;
export const bytesPerKiB = 1024;
export const bytesPerMiB = bytesPerKiB * bytesPerKiB;
export const httpPaths = { api: '/api', integrations: '/integrations' };
export const serverDefaults = { host: '127.0.0.1', port: 3400, envFile: '.env' };
