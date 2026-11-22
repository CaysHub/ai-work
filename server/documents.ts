import { spawn } from 'node:child_process';
import { mkdtemp, readFile, readdir, rmdir, stat, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { errorMessage } from '../shared/types.ts';
import type { Platform } from '../shared/types.ts';
import type { AgentRun, LiveBot, Secrets, StoredTables } from './types.ts';
import { config, parse } from './config.ts';
import { all, get, run, unseal } from './store.ts';
import { previewDocument } from './document-preview.ts';
import { wecomPlainText } from './wecom-text.ts';
import { feishuApp, lark, platformSignal, wecomCall, withMcp } from './platform.ts';

type DocumentArgs = { platform: string; action: string; botId?: string; documentId?: string; title?: string; content?: string };
type DocumentMetadata = { owner: string; documentId: string; title: string };
type FeishuConvertedBlock = { table?: { property?: Record<string, unknown> } };
export function sourceDocuments(platform: string, text: string) {
  const documents = new Map<string, { documentId: string; kind: 'doc' | 'sheet' }>();
  const patterns = {
    wecom: /https:\/\/doc\.weixin\.qq\.com\/(doc|sheet)\/([A-Za-z0-9_-]{1,250})(?=[/?#\s\p{P}]|$)/gu,
    feishu: /https:\/\/[a-z0-9-]+\.feishu\.cn\/(docx)\/([A-Za-z0-9_-]{1,250})(?=[/?#\s\p{P}]|$)/gu,
    dingtalk: /https:\/\/alidocs\.dingtalk\.com\/i\/(nodes)\/([A-Za-z0-9_-]{1,250})(?=[/?#\s\p{P}]|$)/gu,
  };
  if (platform !== 'wecom' && platform !== 'feishu' && platform !== 'dingtalk') return [];
  for (const match of text.matchAll(patterns[platform])) {
    documents.set(match[2], { documentId: match[2], kind: match[1] === 'sheet' ? 'sheet' : 'doc' });
  }
  return [...documents.values()].slice(0, 2);
}

function identifier(value: unknown) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,250}$/.test(value)) throw new Error('文档 ID 无效');
  return value;
}
async function wecomDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
  const { action, documentId: docid, title, content } = args;
  const call = <T>(name: string, params: Record<string, unknown>) => wecomCall<T>(secrets.documentMcp, 'doc', name, params);
  if (action === 'create') return call('doc_create', { doc_type: 'doc',
    doc_name: title.replace(/[<>:"/\\|?*]/g, ' ').trim() || '企微文档',
    content_type: 'text', content: content ? wecomPlainText(content) : '' });
  if (action === 'append') return call('doc_contents_append', { docid, content, content_type: 'markdown' });
  return wecomRead(call, 'doc_contents_get', { docid, content_type: 'markdown' });
}
async function wecomRead<T>(call: <R>(name: string, params: Record<string, unknown>) => Promise<R>, name: string,
  args: Record<string, unknown>, complete: (value: T) => boolean = (value) => typeof (value as { content?: unknown }).content === 'string') {
  const request = await call<T & { taskid?: string; task_id?: string }>(name + '_submit', args);
  const taskid = request.taskid ?? request.task_id;
  if (!taskid && complete(request)) return request;
  if (!taskid) throw new Error('企微未返回文档读取任务号');
  for (let attempt = 0; attempt < 8; attempt++) {
    const data = await call<T>(name + '_query', { taskid });
    if (complete(data)) return data;
    if (attempt < 7) await delay(1000, undefined, { signal: platformSignal.getStore() });
  }
  throw new Error('企微文档读取超时');
}
async function feishuDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
  const token = await feishuApp(secrets);
  const call = <T extends object>(path: string, body?: object) => lark<T>(path, body ? { method: 'POST', token, body } : { token });
  let id = args.documentId;
  let link: string | null = null;
  if (args.action === 'create') {
    const created = await call<{ document?: { document_id?: string; url?: string } }>('/docx/v1/documents', { title: args.title, ...(secrets.folderToken ? { folder_token: secrets.folderToken } : {}) });
    id = identifier(created.document?.document_id);
    link = created.document?.url ?? null;
  }
  if (args.action !== 'read' && args.content) {
    try {
      const blocks = await call<{ first_level_block_ids?: string[]; blocks?: FeishuConvertedBlock[] }>(
        '/docx/v1/documents/blocks/convert', { content_type: 'markdown', content: args.content });
      const children = blocks.first_level_block_ids;
      if (!children?.length || !blocks.blocks?.length) throw new Error('飞书未返回可写入的文档块');
      const descendants = blocks.blocks.map((block) => {
        if (!block.table?.property) return block;
        // Feishu includes merge_info in converted tables and rejects it in descendant writes.
        const { merge_info: _readOnly, ...property } = block.table.property;
        return { ...block, table: { ...block.table, property } };
      });
      await call('/docx/v1/documents/' + id + '/blocks/' + id + '/descendant',
        { children_id: children, descendants, index: -1 });
    } catch (error) {
      throw new Error('飞书文档 ' + id + ' 写入结果待核对。' + errorMessage(error));
    }
  }
  const content = await call<{ content?: string }>('/docx/v1/documents/' + id + '/raw_content');
  if (typeof content.content !== 'string') throw new Error('飞书文档 ' + id + ' 未返回正文');
  if (!link && args.action !== 'read') {
    try {
      const data = await call<{ metas?: { doc_token?: string; url?: string }[] }>('/drive/v1/metas/batch_query', { request_docs: [{ doc_token: id, doc_type: 'docx' }], with_url: true });
      link = data.metas?.find((item) => item.doc_token === id)?.url ?? null;
    } catch { /* An application can have document scope without Drive metadata scope. */ }
  }
  return { documentId: id, url: link, content: content.content };
}
type DwsSearchItem = { nodeId?: string; name?: string; url?: string; docType?: string };
type DwsResult = {
  status?: string; ok?: boolean; success?: boolean; complete?: boolean;
  extension?: string; name?: string; docUrl?: string; sizeBytes?: number;
  documents?: DwsSearchItem[]; hasMore?: boolean; truncated?: boolean;
  content?: { markdown?: string; title?: string; docUrl?: string };
  data?: { verified?: boolean; nodeId?: string; url?: string; docUrl?: string };
};

function dwsProfile(secrets: Secrets, owner: string) {
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

async function dwsDoc(secrets: Secrets, args: DocumentArgs & DocumentMetadata) {
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
  return { documentId: id, url: data.data.url ?? data.data.docUrl ?? 'https://alidocs.dingtalk.com/i/nodes/' + id };
}

function runDws(secrets: Secrets, owner: string, operation: string[], { content = '', download = false } = {}) {
  const profile = dwsProfile(secrets, owner);
  if (!config.dwsExecutable || (!secrets.userToken && !profile)) throw new Error('钉钉云文档连接缺少 DWS 或本人授权');
  const args = [...operation, '--format', 'json'];
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (/^DWS_(ACCESS_TOKEN|TOKEN)$/i.test(key) || (download && config.dwsDownloadProxy && /^(HTTPS?|NO)_PROXY$/i.test(key))) delete env[key];
  }
  if (secrets.userToken) {
    env.DWS_CONFIG_DIR = join(config.dataDir, 'dws');
    env.DWS_ACCESS_TOKEN = secrets.userToken;
  } else if (profile) {
    args.unshift('--profile', profile.name);
  }
  if (download && config.dwsDownloadProxy) {
    env.HTTP_PROXY = config.dwsDownloadProxy;
    env.HTTPS_PROXY = config.dwsDownloadProxy;
  }
  const signal = platformSignal.getStore();
  signal?.throwIfAborted();
  return new Promise<DwsResult>((resolve, reject) => {
    const child = spawn(config.dwsExecutable, args, {
      env, signal, windowsHide: true, shell: false, timeout: 45000, stdio: ['pipe', 'pipe', 'ignore'],
    });
    let output = '';
    child.stdout.setEncoding('utf8').on('data', (chunk) => {
      output += chunk;
      if (output.length > 2_000_000) {
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
        reject(new Error(download
          ? '钉钉文件下载失败' + (config.dwsDownloadProxy ? '，请检查代理连通性或文件权限' : '，请检查文件权限')
          : '钉钉云文档操作失败，请核对本人授权及文档权限'));
      }
    });
  });
}
function documentConnection(row: Pick<AgentRun, 'bot' | 'owner' | 'version'>, platform: Platform, botId?: string) {
  const id = row.bot ?? botId;
  const options = id ? all<StoredTables['bots']>('SELECT * FROM bots WHERE id=? AND owner=? AND platform=? AND enabled=1', id, row.owner, platform) :
    all<StoredTables['bots']>('SELECT * FROM bots WHERE owner=? AND platform=? AND enabled=1', row.owner, platform);
  const candidates = options.map((bot) => ({ ...bot, secrets: unseal<Secrets>(bot.secrets) }))
    .filter(({ owner, secrets }) => platform === 'wecom' ? secrets.documentMcp :
      platform === 'feishu' ? secrets.appId && secrets.appSecret :
        config.dwsExecutable && (secrets.userToken || dwsProfile(secrets, owner)));
  if (candidates.length !== 1) throw new Error(candidates.length ? '有多个文档连接，请指定 botId' : '没有可用的云文档连接');
  const bot = candidates[0];
  if (row.bot && bot.version !== row.version) throw new Error('IM 授权配置已变更');
  return bot.secrets;
}
export function hasDocumentConnection(row: AgentRun) {
  if (!row.bot || row.source === 'web' || row.source === 'schedule') return false;
  try { documentConnection(row, row.source); return true; } catch { return false; }
}
function excelDateTimeCandidates(csv: string) {
  const candidates: Record<string, string> = {};
  // Imported workbooks can return date cells as plain numbers. Keep the raw values and supply conversions for date columns.
  for (const field of csv.matchAll(/"(?:[^"]|"")*"|[^,\r\n]+/g)) {
    const value = field[0];
    if (!/^\d{5}(?:\.\d+)?$/.test(value) || Object.hasOwn(candidates, value)) continue;
    const serial = Number(value);
    if (serial < 25569 || serial >= 73416) continue;
    const minutes = Math.round(serial * 1440);
    candidates[value] = new Date(Date.UTC(1899, 11, 30) + minutes * 60000).toISOString().slice(0, 16).replace('T', ' ');
  }
  return candidates;
}
export async function readWecomSheet(row: AgentRun, { documentId, ranges }: { documentId: string; ranges?: string[] }) {
  if (!row.bot || row.source !== 'wecom') throw new Error('当前会话未绑定企微文档连接');
  const { documentMcp } = documentConnection(row, 'wecom');
  const docid = identifier(documentId);
  const call = <T>(name: string, args: Record<string, unknown>) => wecomCall<T>(documentMcp, 'doc', name, args);
  if (ranges === undefined) return call('sheet_get', { docid });
  if (!Array.isArray(ranges) || !ranges.length || ranges.length > 3) {
    throw new Error('请传入最多 3 个小范围 A1 引用，例如 里程碑计划!A1:J20');
  }
  const requested = ranges.map((reference) => {
    const match = typeof reference === 'string' && /^([^\r\n]{1,200})!([A-Z]{1,3}[1-9]\d{0,6}(?::[A-Z]{1,3}[1-9]\d{0,6})?)$/i.exec(reference.trim());
    if (!match) throw new Error('请使用 工作表名称!A1:J20 或 工作表ID!A1:J20 格式');
    const sheet = match[1].startsWith("'") && match[1].endsWith("'") ? match[1].slice(1, -1).replaceAll("''", "'") : match[1];
    return { sheet, range: match[2].toUpperCase() };
  });
  const workbook = await call<{ url?: string; name?: string; sheets?: { sheet_id: string; title: string }[] }>('sheet_get', { docid });
  const locations = requested.map(({ sheet, range }) => {
    const found = workbook.sheets?.find((item) => item.sheet_id === sheet) ??
      workbook.sheets?.find((item) => item.title === sheet);
    if (!found?.sheet_id) throw new Error('企微工作簿里找不到工作表：' + sheet);
    return { sheet: found.title, sheetId: found.sheet_id, range };
  });
  const results = await Promise.all(locations.map(async ({ sheet, sheetId, range }) => {
    const result = await wecomRead<{ content?: string; truncated_tip?: string }>(call, 'sheet_ranges_get', {
      docid, sheet_id: sheetId, range, mode: 'csv',
    });
    return { sheet, range, content: result.content,
      dateTimeBySerial: excelDateTimeCandidates(result.content ?? ''), truncated: result.truncated_tip || undefined };
  }));
  return { documentId: docid, title: workbook.name, url: workbook.url, ranges: results };
}

export function stageReplyDocument(row: AgentRun, title: string, content: string) {
  if (!hasDocumentConnection(row)) throw new Error('当前会话没有可用的云文档连接');
  if (typeof title !== 'string' || !title.trim() || title.length > 250 ||
    typeof content !== 'string' || !content.trim() || content.length > 100000) throw new Error('云文档标题或正文无效');
  run(`INSERT INTO reply_documents (id,title,content) VALUES (?,?,?)
    ON CONFLICT(id) DO UPDATE SET title=excluded.title,content=excluded.content`, row.id, title.trim(), content.trim());
  return { staged: true, title: title.trim() };
}
type ReplyDocument = { title: string; content: string; state: string; url: string | null };
export async function publishReplyDocument(bot: Pick<LiveBot, 'id' | 'owner' | 'version' | 'platform'>, id: string) {
  const doc = get<ReplyDocument>('SELECT title,content,state,url FROM reply_documents WHERE id=?', id);
  if (!doc) return '';
  const missingLink = '云文档暂未取得可用链接。';
  if (doc.state !== 'draft') return doc.url ?? missingLink;
  if (!run("UPDATE reply_documents SET state='publishing' WHERE id=? AND state='draft'", id).changes) return missingLink;
  try {
    const secrets = documentConnection({ bot: bot.id, owner: bot.owner, version: bot.version }, bot.platform);
    const adapter = { wecom: wecomDoc, feishu: feishuDoc, dingtalk: dwsDoc }[bot.platform];
    const result = await adapter(secrets, { action: 'create', platform: bot.platform, title: doc.title,
      content: doc.content, documentId: '', owner: bot.owner }) as {
      url?: string; doc_url?: string; docurl?: string; data?: { url?: string; nodeId?: string };
    };
    const value = result.url ?? result.doc_url ?? result.docurl ?? result.data?.url ??
      (bot.platform === 'dingtalk' && result.data?.nodeId ? 'https://alidocs.dingtalk.com/i/nodes/' + identifier(result.data.nodeId) : '');
    const url = new URL(value ?? '');
    const hosts = { wecom: /^(?:doc|docs)\.weixin\.qq\.com$/, feishu: /(^|\.)feishu\.cn$/, dingtalk: /(^|\.)dingtalk\.com$/ };
    if (url.protocol !== 'https:' || url.username || url.password || url.port || !hosts[bot.platform].test(url.hostname) || url.href.length > 1000) {
      throw new Error('平台未返回有效的文档链接');
    }
    run("UPDATE reply_documents SET state='published',url=? WHERE id=?", url.href, id);
    return url.href;
  } catch {
    run("UPDATE reply_documents SET state='unconfirmed' WHERE id=?", id);
    return missingLink;
  }
}
export async function documentAction(row: AgentRun, callId: string | null, args: DocumentArgs): Promise<unknown> {
  const { platform, action, botId, title, content } = args;
  if ((platform !== 'wecom' && platform !== 'feishu' && platform !== 'dingtalk') ||
    !['read', 'create', 'append'].includes(action)) throw new Error('文档平台或操作无效');
  const documentId = action === 'create' ? '' : identifier(args.documentId);
  if ((action === 'create' && (!title?.trim() || title.length > 250)) ||
    (action === 'append' && !content?.trim()) || (content?.length ?? 0) > 100000) throw new Error('文档标题或正文无效');
  if (row.bot && action !== 'read') throw new Error('IM 回复文档请使用 reply_document 工具');
  const secrets = documentConnection(row, platform, botId);
  const adapter = { wecom: wecomDoc, feishu: feishuDoc, dingtalk: dwsDoc }[platform];
  const input = { ...args, documentId, title: title ?? '', owner: row.owner };
  if (action === 'read') return adapter(secrets, input);
  const key = row.id + ':' + callId;
  const previous = get<{ state: string; result: string }>('SELECT * FROM tool_writes WHERE id=?', key);
  if (previous?.state === 'done') return parse(previous.result);
  if (previous) throw new Error('该文档操作已提交过，请先读回核对结果');
  run("INSERT INTO tool_writes (id,state) VALUES (?,'pending')", key);
  const result = await adapter(secrets, input);
  run("UPDATE tool_writes SET state='done',result=? WHERE id=?", JSON.stringify(result), key);
  return result;
}
export async function searchCloudDocuments(row: AgentRun, keywords: unknown) {
  if (!row.bot || row.source === 'web' || row.source === 'schedule') throw new Error('当前接管连接没有云文档搜索能力');
  const terms = Array.isArray(keywords) ? [...new Set(keywords.filter((word): word is string => typeof word === 'string').map((word) => word.trim()).filter(Boolean))] : [];
  if (!terms.length || terms.length > 5 || terms.some((word) => word.length > 100)) throw new Error('请提供 1 至 5 个有效文档关键词');
  const secrets = documentConnection(row, row.source);
  if (row.source === 'wecom') return wecomCall(secrets.documentMcp, 'doc', 'doc_search', { keywords: terms });
  if (row.source === 'dingtalk') {
    const results = await Promise.allSettled(terms.map((query) =>
      runDws(secrets, row.owner, ['doc', '+search', '--query=' + query, '--limit', '15'])));
    const successful = results.flatMap((result) =>
      result.status === 'fulfilled' && Array.isArray(result.value.documents) ? [result.value] : []);
    if (!successful.length) throw new Error('钉钉云文档检索失败');
    const matches = new Map<string, DwsSearchItem>();
    for (const data of successful) {
      for (const doc of data.documents ?? []) {
        if (doc.nodeId && doc.name) matches.set(doc.nodeId, doc);
      }
    }
    const documents = [...matches.values()].slice(0, 20).map((doc) =>
      ({ documentId: doc.nodeId, title: doc.name, url: doc.url, type: doc.docType }));
    return { documents, partial: successful.length !== results.length || matches.size > documents.length ||
      successful.some((data) => data.hasMore || data.truncated || data.status === 'partial') };
  }

  const token = await feishuApp(secrets);
  const matches: { documentId: string; title: string; url?: string; modifiedAt?: string; score: number }[] = [];
  const normalize = (value: string) => value.normalize('NFKC').toLowerCase();
  const queries = terms.map(normalize);
  let pageToken: string | undefined;
  const seen = new Set<string>();
  for (let page = 0; page < 10; page++) {
    const params = new URLSearchParams({ page_size: '100' });
    if (secrets.folderToken) params.set('folder_token', secrets.folderToken);
    if (pageToken) params.set('page_token', pageToken);
    const data: { files?: { name?: string; token?: string; type?: string; url?: string; modified_time?: string }[];
      next_page_token?: string; has_more?: boolean } = await lark('/drive/v1/files?' + params, { token });
    for (const file of data.files ?? []) {
      if (file.type !== 'docx' || !file.name || !file.token) continue;
      const name = normalize(file.name);
      const score = queries.filter((word) => name.includes(word)).length;
      if (score) matches.push({ documentId: file.token, title: file.name, url: file.url, modifiedAt: file.modified_time, score });
    }
    pageToken = data.has_more ? data.next_page_token : undefined;
    if (!pageToken || seen.has(pageToken)) break;
    seen.add(pageToken);
  }
  return { documents: matches.sort((a, b) => b.score - a.score || Number(b.modifiedAt ?? 0) - Number(a.modifiedAt ?? 0)).slice(0, 15),
    truncated: Boolean(pageToken) };
}
export function mcpAction(row: AgentRun, { server, name, arguments: args }: { server: string; name?: string; arguments?: Record<string, unknown> }) {
  const entry = config.mcpServers.find((item) => item.name === server && item.owners?.includes(row.owner));
  if (!entry || row.bot) throw new Error('此会话未获该 MCP 服务的授权');
  const token = entry.apiKey;
  return withMcp(entry.url, token ? { Authorization: 'Bearer ' + token } : {}, async (client) => {
    if (!name) {
      const allowed = (tool: { name: string }) => entry.tools?.includes(tool.name);
      const items = [];
      let cursor: string | undefined;
      for (let page = 0; page < 10; page++) {
        const result = await client.listTools({ cursor });
        items.push(...result.tools.filter(allowed));
        if (!result.nextCursor) return items;
        cursor = result.nextCursor;
      }
      return items;
    }
    if (!entry.tools?.includes(name)) throw new Error('该工具未授予当前用户');
    const result = await client.callTool({ name, arguments: args ?? {} });
    if (result.isError) throw new Error('MCP 工具返回失败');
    return result;
  });
}
