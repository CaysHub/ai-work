import { toolDescriptions } from './prompts.ts';
import type { ToolRegistration } from '@earendil-works/pi-durable';
import { defineTool } from '@earendil-works/pi-durable';
import type { ToolEvent } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { now, parse } from '../common/runtime.ts';
import type { AgentRun } from '../common/types.ts';
import { documentAction, mcpAction, readWecomSheet, searchCloudDocuments, stageReplyDocument } from '../documents/cloud.ts';
import { readRunDocument, saveGeneratedDocument } from '../documents/library.ts';
import { createWordDocument } from '../documents/word.ts';
import { listFiles, readText, workspace } from '../files/workspace.ts';
import { platformSignal } from '../platform/http.ts';
import type { WecomDecisionAction } from '../replies/owner-commands.ts';
import { decideWecomReply } from '../replies/owner-commands.ts';
import { skillResource } from '../skills/skills.ts';
import { get, run } from '../storage/store.ts';
import { agentConfig, decisionTool } from './agent.config.ts';
import { schema, stringField as string } from './tool-schema.ts';

function toolPreview(value: unknown) {
  try {
    const reduced = typeof value === 'string' ? value : JSON.stringify(value, (key, item) =>
      /^(?:api[_-]?key|authorization|password|cookie|secret|.*token|.*secret)$/i.test(key) ? '[已隐藏]' :
        /^(extra_identity_context|security_notice)$/.test(key) ? undefined : item, 2);
    const masked = reduced?.replace(/([?&](?:apikey|api_key|token|scode)=)[^\s"'&,]+/gi, '$1[已隐藏]')
      .replace(/\bBearer\s+[\w./+=-]+/gi, 'Bearer [已隐藏]');
    return masked && masked.length > agentConfig.toolPreviewLength ? masked.slice(0, agentConfig.toolPreviewLength) + '\n…（预览已截断）' : masked;
  } catch { return '无法预览此内容'; }
}

export function toolsForRun(row: AgentRun) {
  function tool<T extends object>(name: string, description: string, parameters: object,
    action: (args: T, row: AgentRun, callId: string) => unknown, replay: ToolRegistration['replay'] = 'unsafe') {
    return defineTool({ name, description, parameters, replay, async execute(args, api) {
      platformSignal.getStore()?.throwIfAborted();
      const journal = (state: ToolEvent['state'], output?: unknown) => {
        const saved = get<{ tool_log: string }>('SELECT tool_log FROM runs WHERE id=?', row.id);
        const records = saved ? parse<ToolEvent[]>(saved.tool_log) : [];
        const index = records.findIndex((item) => item.id === api.callId);
        const entry = { id: api.callId, name, state, started: records[index]?.started ?? now(),
          input: records[index]?.input ?? toolPreview(args), output: toolPreview(output) };
        if (index === -1) records.push(entry);
        else records[index] = entry;
        run('UPDATE runs SET tool_log=?,updated=? WHERE id=?', JSON.stringify(records.slice(-agentConfig.toolHistoryLimit)), now(), row.id);
      };
      journal('running');
      try {
        const output = await action(args as T, row, api.callId);
        platformSignal.getStore()?.throwIfAborted();
        journal('done', output);
        const text = typeof output === 'string' ? output : JSON.stringify(output) ?? '';
        return { output: [{ type: 'text', text: text.slice(0, agentConfig.toolResultLength) }] };
      } catch (error) {
        journal('failed', errorMessage(error));
        throw error;
      }
    } });
  }
  return [
    tool<{ path?: string }>('workspace_list', toolDescriptions.workspace_list, schema({ path: string }, []),
      ({ path }, row) => listFiles(workspace(row.chat), path), 'safe'),
    tool<{ path: string }>('workspace_read', toolDescriptions.workspace_read, schema({ path: string }, ['path']),
      ({ path }, row) => readText(workspace(row.chat), path), 'safe'),
    tool<{ path: string; content: string }>('workspace_write', toolDescriptions.workspace_write, schema({ path: string, content: string }, ['path', 'content']),
      ({ path, content }, row) => {
        const document = saveGeneratedDocument(row, path, content);
        return { path, documentId: document.id, saved: true };
      }, 'safe'),
    tool<{ path: string; title: string; content: string }>('word_document_create', toolDescriptions.word_document_create,
      schema({ path: string, title: string, content: string }, ['path', 'title', 'content']),
      async ({ path, title, content }, row) => {
        if (!/\.docx$/i.test(path)) throw new Error('Word 文档的文件名应以 .docx 结尾');
        const bytes = await createWordDocument(title, content);
        platformSignal.getStore()?.throwIfAborted();
        const document = saveGeneratedDocument(row, path, bytes);
        return { path, documentId: document.id, saved: true };
      }, 'safe'),
    tool<{ documentId: string }>('attached_document_read', toolDescriptions.attached_document_read,
      schema({ documentId: string }, ['documentId']), ({ documentId }, row) => readRunDocument(row, documentId), 'safe'),
    tool<{ path: string; skillId: string }>('skill_read', toolDescriptions.skill_read,
      schema({ skillId: string, path: string }, ['skillId', 'path']),
      ({ skillId, path }, row) => skillResource(row.owner, parse(row.skills), skillId, path), 'safe'),
    tool<Parameters<typeof documentAction>[2]>('cloud_document', toolDescriptions.cloud_document,
      schema({ platform: string, action: string, botId: string, documentId: string, title: string, content: string }, ['platform', 'action']),
      async (args, row, callId) => {
        const result = await documentAction(row, callId, args);
        if (args.action !== 'read' && args.content?.trim()) {
          const body = result && typeof result === 'object' && 'content' in result && typeof result.content === 'string'
            ? result.content : args.content;
          const title = (args.title ?? '云文档更新').replace(/[<>:"/\\|?*\u0000-\u001f]/g, ' ').trim().slice(0, agentConfig.cloudResultTitleLength);
          const suffix = callId.replace(/[^a-z0-9_-]/gi, '').slice(-agentConfig.cloudResultNameSuffixLength) || '结果';
          try {
            const local = saveGeneratedDocument(row, '云文档/' + (title || '办公文档') + ' - ' + suffix + '.md', body);
            return { result, localDocumentId: local.id };
          } catch (error) {
            return { result, localDocumentError: errorMessage(error) };
          }
        }
        return result;
      }),
    tool<{ keywords: string[] }>('cloud_document_search', toolDescriptions.cloud_document_search,
      schema({ keywords: { type: 'array', items: string } }, ['keywords']),
      ({ keywords }, row) => searchCloudDocuments(row, keywords), 'safe'),
    tool<{ keywords: string[] }>('wecom_document_search', toolDescriptions.wecom_document_search,
      schema({ keywords: { type: 'array', items: string } }, ['keywords']),
      ({ keywords }, row) => searchCloudDocuments(row, keywords), 'safe'),
    tool<{ documentId: string; ranges?: string[] }>('wecom_sheet_read', toolDescriptions.wecom_sheet_read,
      schema({ documentId: string, ranges: { type: 'array', items: string } }, ['documentId']),
      (args, row) => readWecomSheet(row, args), 'safe'),
    tool<{ title: string; content: string }>('reply_document', toolDescriptions.reply_document,
      schema({ title: string, content: string }, ['title', 'content']),
      ({ title, content }, row) => stageReplyDocument(row, title, content), 'safe'),
    tool<{ action: WecomDecisionAction; text?: string }>(decisionTool, toolDescriptions.replyDecision,
      schema({ action: { type: 'string', enum: ['reply', 'compose', 'skip', 'keep'] }, text: string }, ['action']),
      ({ action, text }, row) => decideWecomReply(row.id, action, text), 'safe'),
    tool<Parameters<typeof mcpAction>[1]>('mcp_tools', toolDescriptions.mcp_tools,
      schema({ server: string, name: string, arguments: { type: 'object' } }, ['server']),
      (args, row) => mcpAction(row, args)),
  ];
}
