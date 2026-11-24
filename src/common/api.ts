import { httpPaths } from '../../shared/config.ts';

export async function api<T = unknown>(path: string, method = 'GET', body?: FormData | object): Promise<T> {
  const form = body instanceof FormData;
  const res = await fetch(httpPaths.api + path, { method,
    headers: body == null || form ? {} : { 'Content-Type': 'application/json' },
    body: form ? body : body == null ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return null as T;
  const data = await res.json().catch(() => ({ error: '工作台服务不可用' }));
  if (!res.ok) throw new Error(data.error ?? '请求失败');
  return data;
}
