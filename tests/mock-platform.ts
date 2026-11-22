import { config } from '../server/config.ts';

const origin = new URL(config.model.baseUrl).origin;
const fetchUpstream = globalThis.fetch;
globalThis.fetch = (input, options) => {
  const url = new URL(input instanceof Request ? input.url : input);
  if (['open.feishu.cn', 'accounts.feishu.cn'].includes(url.hostname)) {
    return fetchUpstream(origin + '/feishu' + url.pathname + url.search, options);
  }
  if (url.hostname === 'qyapi.weixin.qq.com') {
    return fetchUpstream(origin + '/wecom' + url.pathname + url.search, options);
  }
  return fetchUpstream(input, options);
};
