import { AsyncLocalStorage } from 'node:async_hooks';
import type { Secrets } from '../common/types.ts';
import { platformConfig, platformEndpoints } from './platform.config.ts';

type RequestOptions = { method?: string; body?: object; form?: Record<string, string>; token?: string; headers?: Record<string, string> };
export const platformSignal = new AsyncLocalStorage<AbortSignal>();
export function withPlatformSignal<T>(signal: AbortSignal, work: () => T) {
  return platformSignal.run(signal, work);
}
export function requestSignal(timeout: number, signal?: AbortSignal | null) {
  const signals = [AbortSignal.timeout(timeout)];
  const current = platformSignal.getStore();
  if (current) signals.push(current);
  if (signal) signals.push(signal);
  return AbortSignal.any(signals);
}
export async function jsonRequest<T>(url: string | URL, { method = 'GET', body, form, token, headers = {} }: RequestOptions = {}): Promise<T> {
  const response = await fetch(url, {
    method, redirect: 'error', signal: requestSignal(platformConfig.httpTimeoutMs),
    headers: { Accept: 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: form ? new URLSearchParams(form) : body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) throw new Error('平台请求失败，HTTP ' + response.status);
  return response.json();
}
export async function lark<T extends object>(path: string, options?: RequestOptions): Promise<T> {
  const data = await jsonRequest<T & { code: number; data?: T }>(platformEndpoints.feishuApi + path, options);
  if (data.code !== 0) throw new Error('飞书请求失败，错误码 ' + data.code);
  return data.data ?? data;
}
export async function feishuApp(secrets: Secrets) {
  const data = await lark<{ tenant_access_token?: string }>('/auth/v3/tenant_access_token/internal', {
    method: 'POST', body: { app_id: secrets.appId, app_secret: secrets.appSecret },
  });
  if (!data.tenant_access_token) throw new Error('飞书未返回应用令牌');
  return data.tenant_access_token;
}
