import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';
import { z } from 'zod';
import { bytesPerMiB, serverDefaults } from '../../shared/config.ts';
import { defaults } from './defaults.ts';

export const rootDir = fileURLToPath(new URL('../../', import.meta.url));
const source = resolve(serverDefaults.envFile);
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
  host: vars.WORKBENCH_HOST || serverDefaults.host,
  port: read('WORKBENCH_PORT', tcpPort.default(serverDefaults.port)),
  dataDir: resolve(dirname(source), vars.WORKBENCH_DATA_DIR || defaults.dataDir),
  documentMaxBytes: read('WORKBENCH_DOCUMENT_MAX_MB', z.coerce.number().int().min(1).max(defaults.documentMaxSupportedMb).default(defaults.documentMaxMb)) * bytesPerMiB,
  publicBaseUrl: vars.WORKBENCH_PUBLIC_BASE_URL ? read('WORKBENCH_PUBLIC_BASE_URL', url) : '',
  secureCookies: read('WORKBENCH_SECURE_COOKIES', z.enum(['true', 'false']).default('false')) === 'true',
  model: {
    baseUrl: read('MODEL_BASE_URL', url.default(defaults.model.baseUrl)),
    id: vars.MODEL_ID || defaults.model.id,
    apiKey: vars.MODEL_API_KEY || '',
    contextWindow: read('MODEL_CONTEXT_WINDOW', z.coerce.number().int().positive().default(defaults.model.contextWindow)),
    maxTokens: read('MODEL_MAX_TOKENS', z.coerce.number().int().positive().default(defaults.model.maxTokens)),
  },
  dwsExecutable: vars.DWS_EXECUTABLE || '',
  dwsConfigDir: vars.DWS_CONFIG_DIR ? resolve(dirname(source), vars.DWS_CONFIG_DIR) :
    resolve(dirname(source), vars.WORKBENCH_DATA_DIR || defaults.dataDir, defaults.dwsSubdirectory),
  dwsDownloadProxy: vars.DWS_DOWNLOAD_PROXY ? read('DWS_DOWNLOAD_PROXY', url) : '',
  dwsProfiles: array('DWS_PROFILES', z.object({ name: z.string(), owner: z.string() })),
  mcpServers: array('MCP_SERVERS', z.object({
    name: z.string(), url, owners: z.array(z.string()), tools: z.array(z.string()), apiKey: z.string().optional(),
  })),
};
