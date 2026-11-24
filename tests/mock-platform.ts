import { config } from '../server/config/env.ts';
import { registerHooks } from 'node:module';

const origin = new URL(config.model.baseUrl).origin;
const fetchUpstream = globalThis.fetch;
const robot = new URL('./mock-robot.ts', import.meta.url).href;
registerHooks({
  resolve(specifier, context, next) {
    if (['@wecom/aibot-node-sdk', '@larksuiteoapi/node-sdk', 'dingtalk-stream'].includes(specifier)) {
      return { url: robot, shortCircuit: true };
    }
    return next(specifier, context);
  },
});
globalThis.fetch = (input, options) => {
  const url = new URL(input instanceof Request ? input.url : input);
  if (['open.feishu.cn', 'accounts.feishu.cn'].includes(url.hostname)) {
    return fetchUpstream(origin + '/feishu' + url.pathname + url.search, options);
  }
  if (url.hostname === 'qyapi.weixin.qq.com') {
    return fetchUpstream(origin + '/wecom' + url.pathname + url.search, options);
  }
  if (['api.dingtalk.com', 'mcp-gw.dingtalk.com'].includes(url.hostname)) {
    return fetchUpstream(origin + '/dingtalk' + url.pathname + url.search, options);
  }
  return fetchUpstream(input, options);
};
