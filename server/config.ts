import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { z } from 'zod';

export const rootDir = fileURLToPath(new URL('../', import.meta.url));
const source = resolve('.env');
const vars = existsSync(source) ? parseEnv(readFileSync(source, 'utf8')) : {};
function read<T>(key: string, schema: z.ZodType<T>, raw: unknown = vars[key]): T {
  const result = schema.safeParse(raw);
  if (!result.success) throw new Error('.env 配置无效：' + key);
  return result.data;
}
function array<T>(key: string, schema: z.ZodType<T>): T[] {
  let value: unknown;
  try { value = JSON.parse(vars[key] || '[]'); }
  catch { throw new Error('.env 配置无效：' + key); }
  return read(key, z.array(schema), value);
}
const tcpPort = z.coerce.number().int().min(0).max(65535);
const url = z.url({ protocol: /^https?$/ });
export const config = {
  host: vars.WORKBENCH_HOST || '127.0.0.1',
  port: read('WORKBENCH_PORT', tcpPort.default(3400)),
  dataDir: resolve(dirname(source), vars.WORKBENCH_DATA_DIR || './data'),
  documentMaxBytes: read('WORKBENCH_DOCUMENT_MAX_MB', z.coerce.number().int().min(1).max(100).default(25)) * 1024 * 1024,
  publicBaseUrl: vars.WORKBENCH_PUBLIC_BASE_URL ? read('WORKBENCH_PUBLIC_BASE_URL', url) : '',
  secureCookies: read('WORKBENCH_SECURE_COOKIES', z.enum(['true', 'false']).default('false')) === 'true',
  model: {
    baseUrl: read('MODEL_BASE_URL', url.default('https://api.deepseek.com')),
    id: vars.MODEL_ID || 'deepseek-v4-flash',
    apiKey: vars.MODEL_API_KEY || '',
    contextWindow: read('MODEL_CONTEXT_WINDOW', z.coerce.number().int().positive().default(131072)),
    maxTokens: read('MODEL_MAX_TOKENS', z.coerce.number().int().positive().default(8192)),
  },
  dwsExecutable: vars.DWS_EXECUTABLE || '',
  dwsDownloadProxy: vars.DWS_DOWNLOAD_PROXY ? read('DWS_DOWNLOAD_PROXY', url) : '',
  dwsProfiles: array('DWS_PROFILES', z.object({ name: z.string(), owner: z.string() })),
  mcpServers: array('MCP_SERVERS', z.object({
    name: z.string(), url, owners: z.array(z.string()), tools: z.array(z.string()), apiKey: z.string().optional(),
  })),
};
export const now = () => Date.now();
export function parse<T = unknown>(value: string): T {
  return JSON.parse(value) as T;
}
export function hasCode(error: unknown, code: string) {
  return error instanceof Error && 'code' in error && error.code === code;
}
export function httpError(status: number, message: string) {
  return Object.assign(new Error(message), { status });
}
