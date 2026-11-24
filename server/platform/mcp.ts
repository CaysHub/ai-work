import type { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { jsonRequest, platformSignal, requestSignal } from './http.ts';
import { dingServices, platformConfig, platformEndpoints } from './platform.config.ts';
type ToolResult = { [key: string]: unknown; isError?: unknown; structuredContent?: unknown; content?: unknown };
export function unpack<T>(result: ToolResult | undefined): T {
  if (!result || result.isError) throw new Error('平台工具请求失败');
  let output = result.structuredContent as { success?: boolean; errcode?: number; code?: number; result?: T; data?: T } | undefined;
  for (const block of Array.isArray(result.content) ? result.content as { type: string; text?: string }[] : []) {
    if (output) break;
    if (block.type !== 'text' || typeof block.text !== 'string') continue;
    try { output = JSON.parse(block.text); } catch { /* Some servers send an explanatory text block before JSON. */ }
  }
  if (!output || output.success === false || (output.errcode != null && Number(output.errcode) !== 0) ||
    (output.code != null && Number(output.code) !== 0)) {
    const code = Number(output?.errcode ?? output?.code);
    const detail = Number.isSafeInteger(code) && code !== 0 ? '，错误码 ' + code : '';
    throw Object.assign(new Error('平台工具返回失败或响应不完整' + detail), {
      providerRejected: Boolean(output), providerCode: Number.isSafeInteger(code) ? code : undefined,
    });
  }
  return output.result ?? output.data ?? output as T;
}
export async function withMcp<T>(url: string, headers: Record<string, string>, action: (client: Client) => Promise<T>) {
  const [{ Client }, { StreamableHTTPClientTransport }] = await Promise.all([
    import('@modelcontextprotocol/sdk/client/index.js'), import('@modelcontextprotocol/sdk/client/streamableHttp.js'),
  ]);
  const client = new Client(platformConfig.mcpClient);
  const transport = new StreamableHTTPClientTransport(new URL(url), {
    requestInit: { headers, redirect: 'error' },
    fetch: (input, init = {}) => fetch(input, { ...init, redirect: 'error', signal: requestSignal(platformConfig.mcpTimeoutMs, init.signal) }),
  });
  try {
    await client.connect(transport);
    return await action(client);
  } finally {
    await client.close();
  }
}
export function wecomEndpoint(endpoint: string, scope: 'msg' | 'doc') {
  const url = new URL(endpoint);
  if (url.origin !== platformEndpoints.wecomApi || url.pathname !== platformEndpoints.wecomMcpPath + scope ||
    !url.searchParams.get('apikey') || url.searchParams.size !== 1 || url.username || url.password || url.hash) {
    throw new Error('请使用对应能力的企业微信官方 MCP 授权地址');
  }
  return url.href;
}
export const wecomCall = <T>(endpoint: string | undefined, scope: 'msg' | 'doc', name: string, args: Record<string, unknown>): Promise<T> => {
  if (!endpoint) throw new Error('企微 MCP 授权未配置');
  return withMcp(wecomEndpoint(endpoint, scope), {}, async (client) =>
    unpack<T>(await client.callTool({ name, arguments: args }, undefined, { signal: platformSignal.getStore() })));
};
export async function dingCall<T>(token: string | undefined, name: keyof typeof dingServices, args: Record<string, unknown>): Promise<T> {
  if (!token) throw new Error('钉钉访问令牌未配置');
  const data = await jsonRequest<{ id: number; error?: unknown; result?: ToolResult }>(platformEndpoints.dingMcp + dingServices[name], {
    method: 'POST', token, headers: { 'x-user-access-token': token },
    body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } },
  });
  if (data.id !== 1 || data.error) throw new Error('钉钉个人会话接口失败');
  return unpack<T>(data.result);
}
