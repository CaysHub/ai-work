import { agentPrompts } from './prompts.ts';
import { BACKGROUND_CONTEXT as ctx } from '@earendil-works/chord/context';
import type { Model } from '@earendil-works/pi-ai';
import { createModels, createProvider } from '@earendil-works/pi-ai';
import { openAICompletionsApi } from '@earendil-works/pi-ai/api/openai-completions.lazy';
import type { Conversation, EntryRecord } from '@earendil-works/pi-durable';
import { AssistantEntry, Harness, createRegistry, defineExtension } from '@earendil-works/pi-durable';
import { openNodeSqliteStorage } from '@earendil-works/pi-durable/storage/sqlite/node';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import type { RunProgress } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { mayHandleInbound } from '../bots/inbound.ts';
import { now, parse } from '../common/runtime.ts';
import type { AgentRun, StoredTables } from '../common/types.ts';
import { config } from '../config/env.ts';
import { documentAction, hasDocumentConnection, sourceDocuments } from '../documents/cloud.ts';
import { importWorkspaceDocuments, runAttachments } from '../documents/library.ts';
import { workspace } from '../files/workspace.ts';
import { withPlatformSignal } from '../platform/http.ts';
import { commandForRun, restoreWecomPending } from '../replies/owner-commands.ts';
import { reviewWindow } from '../replies/replies.config.ts';
import { isReviewBot, recordDraft, userCanceledReply } from '../replies/reviews.ts';
import { fetchRunWecomFiles } from '../replies/wecom-files.ts';
import { skillInstructions } from '../skills/skills.ts';
import { all, get, run, transaction } from '../storage/store.ts';
import { agentConfig, decisionTool, triageTool } from './agent.config.ts';
import { schema, stringField as string } from './tool-schema.ts';

import { toolsForRun } from './tools.ts';

export function modelReady() {
  return Boolean(config.model.apiKey);
}
function recordProgress(id: string, entries: readonly EntryRecord[]) {
  const updates = entries.flatMap((entry): RunProgress[] => {
    const assistant = entry.kind === 'pi.assistant' ? entry.model?.find((message) => message.role === 'assistant') : undefined;
    if (!assistant || assistant.role !== 'assistant') return [];
    const calls = assistant.content.filter((block) => block.type === 'toolCall').length;
    if (calls) return [{ id: String(entry.id), at: assistant.timestamp, title: '确定下一步',
      summary: 'Pi 根据当前会话和已有资料，选择执行下列 ' + calls + ' 项工具调用。' }];
    if (assistant.stopReason === 'stop') return [{ id: String(entry.id), at: assistant.timestamp, title: '完成本阶段',
      summary: 'Pi 已完成本阶段分析，结果将用于生成回答或提交处理决定。' }];
    return [];
  });
  if (!updates.length) return;
  const saved = get<{ progress_log: string }>('SELECT progress_log FROM runs WHERE id=?', id);
  if (!saved) return;
  const records = new Map(parse<RunProgress[]>(saved.progress_log).map((item) => [item.id, item]));
  for (const item of updates) records.set(item.id, item);
  run('UPDATE runs SET progress_log=? WHERE id=?', JSON.stringify([...records.values()].slice(-agentConfig.progressHistoryLimit)), id);
}
export function openAgent() {
  const models = createModels();
  const { baseUrl, id, apiKey, contextWindow, maxTokens } = config.model;
  const provider = agentConfig.provider;
  const deepseek = new URL(baseUrl).hostname === agentConfig.deepseekHost;
  const api = openAICompletionsApi();
  const configurePayload = (stream: typeof api.stream): typeof api.stream => (model, context, options) => stream(model, context, {
    ...options,
    async onPayload(payload) {
      if (payload && typeof payload === 'object') {
        if (deepseek) Object.assign(payload, { thinking: { type: 'disabled' } });
        const { tools, messages } = payload as { tools?: { function?: { name?: string } }[]; messages?: { role?: string }[] };
        const onlyTool = tools?.length === 1 ? tools[0].function?.name : undefined;
        if (onlyTool === decisionTool || onlyTool === triageTool) {
          const tool_choice = messages?.at(-1)?.role === 'tool' ? 'none' :
            { type: 'function', function: { name: onlyTool } };
          Object.assign(payload, { tool_choice });
        }
      }
      return await options?.onPayload?.(payload, model) ?? payload;
    },
  });
  const model: Model<'openai-completions'> = {
    id, name: id, provider, baseUrl, api: 'openai-completions', reasoning: false, input: ['text'],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow, maxTokens,
    compat: { supportsStore: false, supportsDeveloperRole: false, supportsReasoningEffort: false, maxTokensField: 'max_tokens' },
  };
  models.setProvider(createProvider({
    id: provider, name: provider, baseUrl,
    auth: { apiKey: { name: '模型密钥', resolve: async () => apiKey ? { auth: { apiKey } } : undefined } },
    models: [model],
    api: { ...api, stream: configurePayload(api.stream), streamSimple: configurePayload(api.streamSimple) },
  }));
  type ActiveRun = { id: string; abort: AbortController; cancel?: boolean; shutdown?: boolean; harness?: Harness; root?: Conversation; work?: Promise<void> };
  const active = new Map<string, ActiveRun>();
  async function skipReason(row: AgentRun, signal: AbortSignal) {
    const recent = all<{ prompt: string; reply: string; answer: string | null }>(`
      SELECT substr(prompt,1,${agentConfig.triageContextLength}) AS prompt,reply,CASE WHEN reply='accepted' THEN substr(COALESCE(reply_text,answer),1,${agentConfig.triageContextLength}) END AS answer
      FROM runs WHERE chat=? AND rowid<(SELECT rowid FROM runs WHERE id=?) ORDER BY rowid DESC LIMIT ?`, row.chat, row.id, agentConfig.triageContextRuns).reverse();
    const hasAttachment = Boolean(get('SELECT 1 FROM wecom_inbound_files WHERE run_id=?', row.id));
    try {
      const result = await models.complete(model, {
        systemPrompt: agentPrompts.triage,
        messages: [{ role: 'user', timestamp: row.created, content: JSON.stringify({ recent, message: row.prompt, hasAttachment }) }],
        tools: [{ name: triageTool, description: agentPrompts.triageTool, parameters: schema({
          action: { type: 'string', enum: ['process', 'skip'] }, reason: string,
        }, ['action', 'reason']) }],
      }, { signal: AbortSignal.any([signal, AbortSignal.timeout(agentConfig.triageTimeoutMs)]), maxTokens: agentConfig.triageMaxTokens });
      const call = result.content.find((block) => block.type === 'toolCall' && block.name === triageTool);
      if (result.stopReason === 'toolUse' && call?.type === 'toolCall' && call.arguments.action === 'skip' &&
          typeof call.arguments.reason === 'string' && call.arguments.reason.trim()) {
        return call.arguments.reason.trim().slice(0, agentConfig.triageReasonLength);
      }
    } catch {
      // If classification is unavailable, continue with the normal handling flow.
    }
  }
  async function execute(row: AgentRun, state: ActiveRun) {
    let harness: Harness | undefined;
    let detachProgress: (() => void) | undefined;
    const command = commandForRun(row.id);
    const platform = row.source === 'feishu' ? '飞书' : row.source === 'dingtalk' ? '钉钉' : '企微';
    const context = command ? agentPrompts.boundThreadStart + platform +
      agentPrompts.boundThreadEnd +
      JSON.stringify({ 会话: command.title, 会话ID: command.target, 原来信: command.originalPrompt, 完整草稿: command.draft, 拟交付文档: command.documentTitle }) : '';
    try {
      const allowed = !row.bot || await mayHandleInbound(row);
      state.abort.signal.throwIfAborted();
      if (!allowed) {
        state.cancel = true;
        throw new Error('接管已停用、本人已回复，或当前无法核验原会话');
      }
      if (state.shutdown) return;
      if (!modelReady()) throw new Error('模型密钥未配置，请在 .env 中填写 MODEL_API_KEY 后重启服务');
      if (row.bot && !command && !row.submitted) {
        const reason = await skipReason(row, state.abort.signal);
        state.abort.signal.throwIfAborted();
        if (reason) {
          run("UPDATE runs SET state='done',answer=?,reply='skipped',updated=? WHERE id=? AND reply='waiting'", reason, now(), row.id);
          return;
        }
      }
      const approval = !command && row.bot && isReviewBot(get<Pick<StoredTables['bots'], 'platform' | 'mode'>>('SELECT platform,mode FROM bots WHERE id=? AND version=?', row.bot, row.version));
      const connected = hasDocumentConnection(row);
      const searchable = connected;
      const dir = workspace(row.chat);
      importWorkspaceDocuments(row.owner, row.chat);
      mkdirSync(dir, { recursive: true });
      mkdirSync(join(config.dataDir, agentConfig.storageDirectory), { recursive: true });
      // Pi root IDs are local to each SQLite storage, so tool context must be local to this harness.
      const tools = toolsForRun(row);
      const registry = createRegistry();
      registry.install(defineExtension({ name: provider, tools }));
      harness = await Harness.open(await openNodeSqliteStorage(join(config.dataDir, agentConfig.storageDirectory, row.chat + '.sqlite')), {
        models, registry, settings: { stream: { timeoutMs: agentConfig.streamTimeoutMs }, retry: { maxRetries: agentConfig.maxRetries }, toolExecution: 'sequential' },
      }, ctx);
      state.harness = harness;
      if (state.shutdown) return;
      const root = await harness.root(ctx, { agent: { model: { provider, modelId: id }, maxTurns: agentConfig.maxTurns } });
      state.root = root;
      if (state.cancel) {
        await root.abort(ctx);
        throw new Error('已停止');
      }
      let lastEntry: EntryRecord['id'] | undefined;
      const observation = await harness.observeConversation(root.id, {
        advance(event) {
          if (event.kind !== 'value' || lastEntry === undefined) return;
          const boundary = lastEntry;
          const entries = event.value.entries.filter((entry) => entry.id > boundary);
          if (!entries.length) return;
          lastEntry = entries.at(-1)?.id;
          recordProgress(row.id, entries);
        },
        end() {},
      }, ctx, { min: 1, max: agentConfig.observationMaxEntries });
      lastEntry = observation.value.entries.at(-1)?.id ?? 0 as EntryRecord['id'];
      detachProgress = observation.detach;
      if (!row.submitted) {
        const selectedSkills = skillInstructions(row.owner, parse(row.skills));
        const approvedServers = config.mcpServers.filter((item) => item.owners?.includes(row.owner)).map((item) => item.name);
        const instruction = [
          agentPrompts.system,
          agentPrompts.documentPolicy,
          !row.bot ? agentPrompts.web : '',
          row.bot ? agentPrompts.im : '',
          searchable ? agentPrompts.cloudSearch : '',
          row.source === 'dingtalk' && !connected ? agentPrompts.dingWithoutCloud : '',
          connected ? agentPrompts.replyDocument : '',
          row.source === 'wecom' ? agentPrompts.wecomAttachments : '',
          command && connected && row.source === 'wecom' ? agentPrompts.wecomReplyAttachments : '',
          approval ? agentPrompts.approvalStart + platform + agentPrompts.approvalEnd : '',
          command ? agentPrompts.instructionStart + platform + agentPrompts.instructionEnd + JSON.stringify(command.instruction) : '',
          context,
          command?.direct_text ? agentPrompts.directTextStart + platform + agentPrompts.directTextEnd + JSON.stringify(command.direct_text) : '',
          !row.bot && approvedServers.length ? agentPrompts.approvedServers + approvedServers.join('、') : '',
          selectedSkills,
        ].filter(Boolean).join('\n\n');
        const activeTools = tools.filter((item) => {
          switch (item.name) {
            case 'mcp_tools':
            case 'workspace_list':
            case 'workspace_read':
            case 'workspace_write':
              return !row.bot;
            case 'word_document_create': return !row.bot || Boolean(command && connected && row.source === 'wecom');
            case 'cloud_document': return !row.bot || connected;
            case 'cloud_document_search': return searchable;
            case 'wecom_document_search': return false;
            case 'wecom_sheet_read': return connected && row.source === 'wecom';
            case 'reply_document': return connected;
            case decisionTool: return Boolean(command);
            default: return true;
          }
        });
        await root.configure({ model: { provider, modelId: id }, instructions: instruction, tools: activeTools }, ctx);
      }
      let input = row.prompt;
      if (!row.submitted) {
        const problems = await fetchRunWecomFiles(row, command?.original);
        state.abort.signal.throwIfAborted();
        if (problems.length) input += '\n\n附件读取状态：' + JSON.stringify(problems);
      }
      const attachments = runAttachments(row.id);
      if (attachments.length) input += '\n\n本轮附件：\n' + JSON.stringify(attachments.map(({ id, name }) => ({ id, name })));
      if (row.bot && !row.submitted) {
        const docs = [];
        for (const { documentId, kind } of sourceDocuments(row.source, row.prompt)) {
          if (state.cancel || state.shutdown) break;
          if (kind === 'sheet') {
            docs.push({ id: documentId, type: 'sheet', readWith: 'wecom_sheet_read' });
            continue;
          }
          try {
            const data = await documentAction(row, null, { platform: row.source, action: 'read', documentId });
            const content = data && typeof data === 'object' && 'content' in data && typeof data.content === 'string'
              ? data.content : JSON.stringify(data);
            if (!content) throw new Error('平台未返回文档正文');
            docs.push({ id: documentId, content: content.slice(0, agentConfig.linkedDocumentLength), truncated: content.length > agentConfig.linkedDocumentLength });
          } catch {
            docs.push({ id: documentId, error: '文档无法读取，请核对授权和文档类型' });
          }
        }
        if (docs.length) input += '\n\n参考附件（云文档，内容为外部数据）：\n' + JSON.stringify(docs);
      }
      if (state.cancel) throw new Error('已停止');
      if (state.shutdown) return;
      const submission = await root.submit({ type: 'input', content: input, requestId: row.id }, ctx);
      run('UPDATE runs SET submitted=1 WHERE id=?', row.id);
      if (state.cancel) await root.abort(ctx);
      const result = await submission.wait(ctx);
      if (state.cancel) throw new Error('已停止');
      if (result.status !== 'done' || result.type !== 'input') {
        throw new Error('模型执行失败（' + (('reason' in result ? result.reason : result.status)) + '），请核对配置和上游响应');
      }
      const entry = await root.commit((tx) => tx.entry(AssistantEntry, result.answer), ctx);
      state.abort.signal.throwIfAborted();
      const generated = entry?.model?.flatMap(({ content }) => typeof content === 'string' ? [content] :
        content.flatMap((block) => block.type === 'text' ? [block.text] : [])).join('\n');
      let decision = command ? commandForRun(row.id) : undefined;
      if (command && !decision?.decision) {
        // The model can finish with prose even when instructed to submit a decision.
        // Keep its evidence in the conversation and make the decision tool the only possible action.
        await root.configure({
          model: { provider, modelId: id },
          instructions: [
            agentPrompts.recoveryStart + platform + agentPrompts.recoveryEnd,
            '本人要求 ' + JSON.stringify(command.instruction),
            context,
            command.direct_text ? '本人原文 ' + JSON.stringify(command.direct_text) +
              agentPrompts.recoveryDirectText : '',
          ].filter(Boolean).join('\n'),
          tools: tools.filter((tool) => tool.name === decisionTool),
        }, ctx);
        if (state.cancel) throw new Error('已停止');
        if (state.shutdown) return;
        const recovery = await root.submit({
          type: 'input', content: agentPrompts.recoveryRequestStart + platform + agentPrompts.recoveryRequestEnd, requestId: row.id + ':decision',
        }, ctx);
        if (state.cancel) await root.abort(ctx);
        await recovery.wait(ctx);
        if (state.cancel) throw new Error('已停止');
        if (state.shutdown) return;
        decision = commandForRun(row.id);
        if (!decision?.decision) throw new Error('模型已完成分析，但两次均未提交' + platform + '回复决定');
      }
      const answer = command ? decision?.text : generated;
      if (!answer?.trim()) throw new Error('模型未返回可显示的文本');
      transaction(() => {
        if (approval) recordDraft(row.id, answer);
        if (decision?.decision === 'keep') restoreWecomPending(decision.original);
        run(`UPDATE runs SET state='done',answer=?,updated=?,reply=CASE WHEN reply='waiting' THEN ? ELSE reply END WHERE id=?`,
          answer, now(), approval ? 'awaiting_review' : decision?.decision === 'skip' ? 'skipped' :
            decision?.decision === 'keep' ? 'canceled' : 'pending', row.id);
      });
    } catch (error) {
      if (!state.shutdown) {
        const reason = errorMessage(error).replace(/https?:\/\/\S+/gi, '[地址]').slice(0, agentConfig.failureMessageLength);
        transaction(() => {
          if (get('SELECT 1 FROM runs WHERE id=? AND error=?', row.id, userCanceledReply)) return;
          if (command) restoreWecomPending(command.original);
          run(`UPDATE runs SET state=?,error=?,updated=?,reply=CASE WHEN reply='waiting' THEN 'canceled' ELSE reply END WHERE id=?`,
            state.cancel ? 'canceled' : 'failed', reason, now(), row.id);
        });
      }
    } finally {
      detachProgress?.();
      try {
        if (harness) await harness.close(ctx);
      } finally { active.delete(row.chat); }
    }
  }
  transaction(() => {
    const recoverable = all<{ id: string }>(`
      SELECT r.id FROM runs r JOIN wecom_commands w ON w.id=r.id JOIN runs original ON original.id=w.original
      JOIN reply_reviews review ON review.id=original.id JOIN chats c ON c.id=r.chat JOIN bots b ON b.id=c.bot
      WHERE r.state='failed' AND r.reply='canceled' AND r.error='模型未返回可显示的文本' AND r.submitted=1
        AND w.decision IS NULL AND review.state='commanded' AND original.created>=?
        AND b.enabled=1 AND c.version=b.version AND b.mode='personal'`, now() - reviewWindow);
    for (const { id } of recoverable) {
      run("UPDATE runs SET state='queued',reply='waiting',error=NULL,updated=? WHERE id=?", now(), id);
      run('UPDATE wecom_commands SET feedback=0 WHERE id=?', id);
    }
  });
  return {
    tick() {
      const rows = all<AgentRun>(`SELECT r.*,c.owner,c.bot,c.version,c.target,c.source FROM runs r JOIN chats c ON c.id=r.chat
        WHERE r.state IN ('queued','running') AND NOT EXISTS (
          SELECT 1 FROM runs earlier WHERE earlier.chat=r.chat AND earlier.state IN ('queued','running')
          AND (earlier.created<r.created OR (earlier.created=r.created AND earlier.rowid<r.rowid))
        ) ORDER BY r.created LIMIT ?`, agentConfig.queuedBatchSize);
      for (const row of rows) {
        if (active.size >= 4) break;
        if (active.has(row.chat)) continue;
        const state: ActiveRun = { id: row.id, abort: new AbortController() };
        active.set(row.chat, state);
        run("UPDATE runs SET state='running',updated=? WHERE id=?", now(), row.id);
        state.work = withPlatformSignal(state.abort.signal, () => execute(row, state))
          .catch((error) => console.error('Agent cleanup:', error.name));
      }
    },
    async cancel(chat: string, id: string) {
      const state = active.get(chat);
      if (state?.id === id) {
        state.cancel = true;
        state.abort.abort(new Error('已停止'));
        if (state.root) await state.root.abort(ctx);
      } else {
        run(`UPDATE runs SET state='canceled',updated=?,reply='canceled' WHERE chat=? AND id=? AND state IN ('queued','running')`, now(), chat, id);
      }
    },
    async close() {
      const states = [...active.values()];
      for (const state of states) {
        state.shutdown = true;
        state.abort.abort(new Error('服务正在关闭'));
      }
      await Promise.allSettled(states.map(async (state) => {
        if (state.harness) await state.harness.close(ctx);
        await state.work;
      }));
    },
  };
}
