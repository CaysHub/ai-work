import { spawn } from 'node:child_process';
import { mkdtemp, readFile, readdir, rmdir, stat, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Secrets } from '../common/types.ts';
import { config } from '../config/env.ts';
import { platformSignal } from '../platform/http.ts';
import { platformEndpoints } from '../platform/platform.config.ts';
import { documentConfig } from './documents.config.ts';
import type { DocumentArgs, DocumentMetadata } from './identifiers.ts';
import { identifier } from './identifiers.ts';
import { previewDocument } from './preview.ts';

type FeishuConvertedBlock = { table?: { property?: Record<string, unknown> } };
export type DwsSearchItem = { nodeId?: string; name?: string; url?: string; docType?: string };
type DwsResult = {
  status?: string; ok?: boolean; success?: boolean; complete?: boolean;
  extension?: string; name?: string; docUrl?: string; sizeBytes?: number;
  documents?: DwsSearchItem[]; hasMore?: boolean; truncated?: boolean;
  content?: { markdown?: string; title?: string; docUrl?: string };
  data?: { verified?: boolean; nodeId?: string; url?: string; docUrl?: string };
};

export function dwsProfile(secrets: Secrets, owner: string) {
  return config.dwsProfiles.find((item) => item.name === secrets.dwsProfile && item.owner === owner);
}

async function dwsReadFile(secrets: Secrets, owner: string, documentId: string, metadata: DwsResult, extension: string) {
  if ((metadata.sizeBytes ?? 0) > config.documentMaxBytes) throw new Error('钉钉文件超过读取大小限制');
  const dir = await mkdtemp(join(tmpdir(), 'workbench-dws-'));
  const name = 'document.' + (extension === 'axls' ? 'xlsx' : extension);
  const output = join(dir, name);
  const operation = ['--node', documentId, '--output', output];
  try {
    const command = extension === 'axls' ? ['sheet', 'export', ...operation] : ['drive', 'download', '--no-resume', ...operation];
    await runDws(secrets, owner, command, { download: true });
    if ((await stat(output)).size > config.documentMaxBytes) throw new Error('钉钉文件超过读取大小限制');
    const preview = previewDocument(name, await readFile(output));
    if (preview.kind !== 'text') throw new Error('钉钉文件已下载，正文解析失败');
    return { documentId, title: metadata.name, url: metadata.docUrl, content: preview.text, truncated: preview.truncated };
  } finally {
    for (const file of await readdir(dir).catch(() => [])) await unlink(join(dir, file)).catch(() => {});
    await rmdir(dir).catch(() => {});
  }
}

export async function dwsDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
  const { action, documentId, title, content } = args;
  if (action === 'read') {
    const metadata = await runDws(secrets, args.owner, ['doc', 'info', '--node', documentId]);
    const extension = metadata.extension?.toLowerCase().replace(/^\./, '') ?? '';
    if (['xlsx', 'docx', 'axls'].includes(extension)) return dwsReadFile(secrets, args.owner, documentId, metadata, extension);
    const data = await runDws(secrets, args.owner, ['doc', '+fetch', '--node', documentId, '--scope', 'full']);
    if (typeof data.content?.markdown !== 'string') throw new Error('钉钉未返回可读取的文档正文');
    return { documentId, title: data.content.title ?? metadata.name, url: data.content.docUrl ?? metadata.docUrl,
      content: data.content.markdown, truncated: data.complete === false };
  }
  const operation = action === 'create' ? ['+create', '--name', title, '--doc-format', 'markdown', '--content', '-'] :
    ['+update', '--node', documentId, '--command', 'append', '--doc-format', 'markdown', '--content', '-', '--yes'];
  const data = await runDws(secrets, args.owner, ['doc', ...operation], { content });
  if (!data.complete || !data.ok || !data.data?.verified) throw new Error('钉钉未确认写入，请读回核对结果');
  const id = identifier(data.data.nodeId ?? documentId);
  return { documentId: id, url: data.data.url ?? data.data.docUrl ?? platformEndpoints.dingDoc + id };
}

export function runDws(secrets: Secrets, owner: string, operation: string[], { content = '', download = false } = {}) {
  const profile = dwsProfile(secrets, owner);
  if (!config.dwsExecutable || (!secrets.userToken && !profile)) throw new Error('钉钉云文档连接缺少 DWS 或本人授权');
  const args = [...operation, '--format', 'json'];
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (/^DWS_(ACCESS_TOKEN|TOKEN)$/i.test(key) || (download && config.dwsDownloadProxy && /^(HTTPS?|NO)_PROXY$/i.test(key))) delete env[key];
  }
  env.DWS_CONFIG_DIR = config.dwsConfigDir;
  if (profile) {
    args.unshift('--profile', profile.name);
  } else if (secrets.userToken) {
    env.DWS_ACCESS_TOKEN = secrets.userToken;
  }
  if (download && config.dwsDownloadProxy) {
    env.HTTP_PROXY = config.dwsDownloadProxy;
    env.HTTPS_PROXY = config.dwsDownloadProxy;
  }
  const signal = platformSignal.getStore();
  signal?.throwIfAborted();
  return new Promise<DwsResult>((resolve, reject) => {
    const child = spawn(config.dwsExecutable, args, {
      env, signal, windowsHide: true, shell: false, timeout: documentConfig.dwsTimeoutMs, stdio: ['pipe', 'pipe', 'pipe'],
    });
    let output = '';
    let stderr = '';
    child.stderr.setEncoding('utf8').on('data', (chunk) => {
      if (stderr.length < documentConfig.dwsStderrLength) stderr += chunk.slice(0, documentConfig.dwsStderrLength - stderr.length);
    });
    child.stdout.setEncoding('utf8').on('data', (chunk) => {
      output += chunk;
      if (output.length > documentConfig.dwsOutputLength) {
        child.kill();
        reject(new Error('DWS 输出超过限制'));
      }
    });
    child.on('error', () => reject(signal?.aborted ? signal.reason : new Error('无法启动 DWS CLI')));
    child.stdin.on('error', () => {});
    child.stdin.end(content);
    child.on('close', (code) => {
      try {
        const data = JSON.parse(output) as DwsResult | null;
        if (code !== 0 || !data || typeof data !== 'object' || data.ok === false || data.success === false || data.status === 'error') throw new Error();
        resolve(data);
      } catch {
        const authRejected = /"server_error_code"\s*:\s*"TOKEN_VERIFIED_FAILED"/.test(stderr);
        reject(Object.assign(new Error(authRejected
          ? '钉钉文档服务拒绝当前授权（TOKEN_VERIFIED_FAILED），请核对组织的 CLI 数据访问权限和文档授权'
          : download ? '钉钉文件下载失败，请检查代理连通性或文件权限'
            : '钉钉文档接口未完成本次操作'), { authRejected }));
      }
    });
  });
}
