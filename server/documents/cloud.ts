import type { Platform } from '../../shared/types.ts';
import { parse } from '../common/runtime.ts';
import type { AgentRun, LiveBot, Secrets, StoredTables } from '../common/types.ts';
import { config } from '../config/env.ts';
import { feishuApp, lark } from '../platform/http.ts';
import { wecomCall, withMcp } from '../platform/mcp.ts';
import { platformEndpoints } from '../platform/platform.config.ts';
import { all, get, run, unseal } from '../storage/store.ts';
import { documentConfig } from './documents.config.ts';
import type { DocumentArgs } from './identifiers.ts';
import { identifier } from './identifiers.ts';

import type { DwsSearchItem } from './dingtalk.ts';
import { dwsDoc, dwsProfile, runDws } from './dingtalk.ts';
import { feishuDoc } from './feishu.ts';
import { wecomDoc, wecomRead } from './wecom.ts';

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
  return [...documents.values()].slice(0, documentConfig.sourceLinkLimit);
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
  if (!Array.isArray(ranges) || !ranges.length || ranges.length > documentConfig.wecomRangeLimit) {
    throw new Error('请传入最多 ' + documentConfig.wecomRangeLimit + ' 个小范围 A1 引用，例如 里程碑计划!A1:J20');
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
  if (typeof title !== 'string' || !title.trim() || title.length > documentConfig.cloudTitleLength ||
    typeof content !== 'string' || !content.trim() || content.length > documentConfig.cloudTextLength) throw new Error('云文档标题或正文无效');
  run(`INSERT INTO reply_documents (id,title,content) VALUES (?,?,?)
    ON CONFLICT(id) DO UPDATE SET title=excluded.title,content=excluded.content`, row.id, title.trim(), content.trim());
  return { staged: true, title: title.trim() };
}
type ReplyDocument = { title: string; content: string; state: string; url: string | null };
export async function publishReplyDocument(bot: Pick<LiveBot, 'id' | 'owner' | 'version' | 'platform'>, id: string) {
  const doc = get<ReplyDocument>('SELECT title,content,state,url FROM reply_documents WHERE id=?', id);
  if (!doc) return '';
  if (doc.state === 'published' && doc.url) return doc.url;
  if (doc.state !== 'draft' || !run("UPDATE reply_documents SET state='publishing' WHERE id=? AND state='draft'", id).changes) {
    throw new Error('已有云文档创建尝试，需核对原文档及链接');
  }
  try {
    const secrets = documentConnection({ bot: bot.id, owner: bot.owner, version: bot.version }, bot.platform);
    const adapter = { wecom: wecomDoc, feishu: feishuDoc, dingtalk: dwsDoc }[bot.platform];
    const result = await adapter(secrets, { action: 'create', platform: bot.platform, title: doc.title,
      content: doc.content, documentId: '', owner: bot.owner }) as {
      url?: string; doc_url?: string; docurl?: string; data?: { url?: string; nodeId?: string };
    };
    const value = result.url ?? result.doc_url ?? result.docurl ?? result.data?.url ??
      (bot.platform === 'dingtalk' && result.data?.nodeId ? platformEndpoints.dingDoc + identifier(result.data.nodeId) : '');
    const url = new URL(value ?? '');
    const hosts = { wecom: /^(?:doc|docs)\.weixin\.qq\.com$/, feishu: /(^|\.)feishu\.cn$/, dingtalk: /(^|\.)dingtalk\.com$/ };
    if (url.protocol !== 'https:' || url.username || url.password || url.port || !hosts[bot.platform].test(url.hostname) || url.href.length > documentConfig.cloudLinkLength) {
      throw new Error('平台未返回有效的文档链接');
    }
    run("UPDATE reply_documents SET state='published',url=? WHERE id=?", url.href, id);
    return url.href;
  } catch (error) {
    const rejectedAuthorization = error instanceof Error && 'authRejected' in error && error.authRejected === true;
    run("UPDATE reply_documents SET state=? WHERE id=? AND state='publishing'", rejectedAuthorization ? 'draft' : 'unconfirmed', id);
    throw error;
  }
}
export async function documentAction(row: AgentRun, callId: string | null, args: DocumentArgs): Promise<unknown> {
  const { platform, action, botId, title, content } = args;
  if ((platform !== 'wecom' && platform !== 'feishu' && platform !== 'dingtalk') ||
    !['read', 'create', 'append'].includes(action)) throw new Error('文档平台或操作无效');
  const documentId = action === 'create' ? '' : identifier(args.documentId);
  if ((action === 'create' && (!title?.trim() || title.length > documentConfig.cloudTitleLength)) ||
    (action === 'append' && !content?.trim()) || (content?.length ?? 0) > documentConfig.cloudTextLength) throw new Error('文档标题或正文无效');
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
  if (!terms.length || terms.length > documentConfig.searchKeywords || terms.some((word) => word.length > documentConfig.searchKeywordLength)) throw new Error('请提供 1 至 ' + documentConfig.searchKeywords + ' 个有效文档关键词');
  const secrets = documentConnection(row, row.source);
  if (row.source === 'wecom') return wecomCall(secrets.documentMcp, 'doc', 'doc_search', { keywords: terms });
  if (row.source === 'dingtalk') {
    const results = await Promise.allSettled(terms.map((query) =>
      runDws(secrets, row.owner, ['doc', '+search', '--query=' + query, '--limit', '15'])));
    const successful = results.flatMap((result) =>
      result.status === 'fulfilled' && Array.isArray(result.value.documents) ? [result.value] : []);
    if (!successful.length) {
      const authFailure = results.find((item) => item.status === 'rejected' && item.reason?.authRejected);
      if (authFailure?.status === 'rejected') throw authFailure.reason;
      throw new Error('钉钉云文档检索未完成');
    }
    const matches = new Map<string, DwsSearchItem>();
    for (const data of successful) {
      for (const doc of data.documents ?? []) {
        if (doc.nodeId && doc.name) matches.set(doc.nodeId, doc);
      }
    }
    const documents = [...matches.values()].slice(0, documentConfig.dingSearchResults).map((doc) =>
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
  for (let page = 0; page < documentConfig.feishuSearchPages; page++) {
    const params = new URLSearchParams({ page_size: String(documentConfig.feishuSearchPageSize) });
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
  return { documents: matches.sort((a, b) => b.score - a.score || Number(b.modifiedAt ?? 0) - Number(a.modifiedAt ?? 0)).slice(0, documentConfig.feishuSearchResults),
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
      for (let page = 0; page < documentConfig.mcpToolListPages; page++) {
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
