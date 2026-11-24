import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { ChildProcess } from 'node:child_process';
import type { Bot, BotActivity, BotConfiguration, ChatRun, DocumentPreview, DocumentRecord, ReplyPage, ReplyRecord, Schedule, Skill } from '../shared/types.ts';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { DatabaseSync } from 'node:sqlite';
import AdmZip from 'adm-zip';
import { mockModel } from './mock-model.ts';
import { mockWecom } from './mock-wecom.ts';
import { wecomPlainText, splitWecomReply } from '../server/replies/wecom-text.ts';

async function eventually<T>(check: () => T | false | undefined | Promise<T | false | undefined>, wait = 12000): Promise<T> {
  const deadline = Date.now() + wait;
  let result: T | false | undefined;
  while (Date.now() < deadline) {
    result = await check();
    if (result) return result;
    await sleep(100);
  }
  throw new Error('操作未在验收期限内完成，最近结果 ' + JSON.stringify(result));
}
test('Windows 工作台完整链路', async (t) => {
  const folder = await mkdtemp(join(tmpdir(), 'ai-work-test-'));
  const model = createServer();
  const held = await mockModel(model);
  const wecom = mockWecom(model);
  const incoming: { chat_id: string; sender: { id: string; sender_type: string }; msg_type: string; body: { content: string }; create_time: string; message_id: string; mentions?: { id: string }[]; parent_id?: string }[] = [];
  const written: string[] = [];
  const delivered: { receive_id: string; content: string; uuid: string }[] = [];
  const notices: { id: string; text: string }[] = [];
  const botEvents = { wecom: [] as object[], feishu: [] as object[], dingtalk: [] as object[] };
  const wecomNotices: { target: string; text: string }[] = [];
  let wecomProactiveLimited = false;
  const dingNotices: { target: string; text: string }[] = [];
  const dingDelivered: { token?: string; target: string; text: string }[] = [];
  const dingIncoming: { openConversationId: string; openMessageId: string; createTime: number;
    sendType: string; senderOpenDingTalkId: string; content: string }[] = [];
  model.on('request', async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://localhost');
    if (!/^\/(feishu|robot|dingtalk)\//.test(url.pathname)) return;
    let raw = '';
    for await (const chunk of request) raw += chunk;
    const body = raw ? JSON.parse(raw) : null;
    const path = url.pathname.replace('/feishu', '');
    function send(data: object, status = 200) {
      response.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(data));
    }
    if (url.pathname.startsWith('/robot/')) {
      const platform = url.pathname.split('/').at(-1) as keyof typeof botEvents;
      if (request.method === 'GET') return send(botEvents[platform].splice(0));
      if (platform === 'wecom') {
        if (wecomProactiveLimited && !body.passive) return send({ errcode: 846607, errmsg: 'aibot send msg frequency limit exceeded' });
        wecomNotices.push({ target: body.target, text: body.body.markdown.content });
        return send({ headers: { req_id: 'wecom-notice-' + wecomNotices.length }, errcode: 0 });
      }
    }
    if (url.pathname === '/dingtalk/v1.0/oauth2/accessToken') {
      assert.equal(body.appSecret, 'fixture-ding-app-secret');
      return send({ accessToken: 'app-ding-token', expireIn: 7200 });
    }
    if (url.pathname === '/dingtalk/v1.0/robot/oToMessages/batchSend') {
      assert.equal(request.headers['x-acs-dingtalk-access-token'], 'app-ding-token');
      dingNotices.push({ target: body.userIds[0], text: JSON.parse(body.msgParam).content });
      return send({ processQueryKey: 'ding-notice-' + dingNotices.length });
    }
    if (url.pathname.startsWith('/dingtalk/server/')) {
      const token = request.headers['x-user-access-token'];
      assert.equal(token, 'employee-ding-token');
      const { name, arguments: args } = body.params;
      const result = (data: object) => send({ id: 1, result: { structuredContent: data } });
      if (name === 'get_current_user_profile') return result([{ orgEmployeeModel: { openDingTalkId: 'ding-owner' } }]);
      if (name === 'list_conversation_message_v2') return result({ hasMore: false, messages: dingIncoming.filter((item) =>
        item.openConversationId === args.openconversation_id && item.createTime <= Number(args.time)) });
      if (name === 'send_personal_message') {
        dingDelivered.push({ token, target: args.openConversationId, text: JSON.parse(args.content).content });
        return result({ openConversationId: args.openConversationId, openMessageId: 'ding-personal-reply' });
      }
    }
    if (path === '/oauth/v3/token' && body.code === 'fixture-code') {
      return send({ code: 0, access_token: 'employee-fixture', refresh_token: 'employee-refresh', expires_in: 3600 });
    }
    if (request.headers.authorization === 'Bearer employee-fixture') {
      if (path === '/open-apis/authen/v1/user_info') return send({ code: 0, data: { open_id: 'ou_fixture' } });
      if (path === '/open-apis/im/v1/messages') {
        if (request.method === 'GET') {
          const start = Number(url.searchParams.get('start_time')) * 1000;
          const end = Number(url.searchParams.get('end_time')) * 1000;
          const target = url.searchParams.get('container_id');
          return send({ code: 0, data: { items: incoming.filter((row) =>
            row.chat_id === target && Number(row.create_time) >= start && Number(row.create_time) <= end), has_more: false } });
        }
        delivered.push(body);
        return send({ code: 0, data: { message_id: 'om_reply' } });
      }
    }
    if (path === '/open-apis/auth/v3/tenant_access_token/internal' && body.app_secret === 'fixture-app-secret') {
      return send({ code: 0, tenant_access_token: 'application-fixture' });
    }
    if (request.headers.authorization === 'Bearer application-fixture') {
      if (path === '/open-apis/im/v1/messages') {
        if (body.receive_id !== 'ou_fixture' || url.searchParams.get('receive_id_type') !== 'open_id') return send({ code: 1 }, 400);
        const id = 'om_bot_' + notices.length;
        notices.push({ id, text: JSON.parse(body.content).text });
        return send({ code: 0, data: { chat_id: 'oc_robot_fixture', message_id: id } });
      }
      const doc = '/open-apis/docx/v1/documents';
      if (path === doc) return send({ code: 0, data: { document: { document_id: 'mock_doc' } } });
      if (path === doc + '/blocks/convert') return send({ code: 0, data: {
        first_level_block_ids: ['block', 'table'],
        blocks: [{ block_id: 'block', content: body.content }, { block_id: 'table', block_type: 31,
          table: { property: { row_size: 1, column_size: 1, merge_info: [{ row_span: 1, col_span: 1 }] } } }],
      } });
      if (path === doc + '/mock_doc/blocks/mock_doc/descendant') {
        const property = body.descendants.find((block: { block_id: string }) => block.block_id === 'table')?.table?.property;
        if (!property || 'merge_info' in property || property.row_size !== 1 || property.column_size !== 1) return send({ code: 1770001 });
        written.push(body.descendants[0].content);
        return send({ code: 0 });
      }
      if (path === doc + '/mock_doc/raw_content') return send({ code: 0, data: { content: written.join('') } });
      if (path === doc + '/feishu_source_doc/raw_content') return send({ code: 0, data: { content: '飞书验收资料正文' } });
      if (path === '/open-apis/drive/v1/files') return send({ code: 0, data: {
        files: [{ name: '一期验收', type: 'docx', token: 'feishu_source_doc' }], has_more: false,
      } });
      if (path === '/open-apis/drive/v1/metas/batch_query') return send({ code: 0, data: { metas: [{ doc_token: 'mock_doc', url: 'https://fixture.feishu.cn/docx/mock_doc' }] } });
    }
    return send({ code: 1 }, 404);
  });
  model.listen(0, '127.0.0.1');
  await once(model, 'listening');
  const address = model.address();
  assert.ok(address && typeof address !== 'string');
  await writeFile(join(folder, '.env'), [
    'WORKBENCH_PORT=0', 'WORKBENCH_PUBLIC_BASE_URL=https://workbench.example.test',
    'MODEL_BASE_URL=http://127.0.0.1:' + address.port + '/v1', 'MODEL_ID=fixture', 'MODEL_API_KEY=local-test-only',
  ].join('\n'));
  let child: ChildProcess | undefined;
  let base = '';
  async function start() {
    const preloader = new URL('./mock-platform.ts', import.meta.url).href;
    const entry = fileURLToPath(new URL('../server/index.ts', import.meta.url));
    const processHandle = spawn(process.execPath, ['--import', preloader, entry], {
      cwd: folder,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child = processHandle;
    let output = '';
    processHandle.stderr.on('data', (chunk) => { output += chunk; });
    processHandle.stdout.on('data', (chunk) => { output += chunk; });
    base = await eventually(() => {
      if (processHandle.exitCode != null) throw new Error(output);
      return output.match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
    });
  }
  async function stop() {
    if (child && child.exitCode === null) {
      child.kill();
      await once(child, 'exit');
    }
  }
  t.after(async () => { await stop(); model.close(); await rm(folder, { recursive: true, force: true, maxRetries: 5 }); });
  await start();
  const request = async <T = unknown>(path: string, { method = 'GET', body, cookie }: { method?: string; body?: object; cookie?: string } = {}) => {
    const form = body instanceof FormData;
    const response = await fetch(base + '/api' + path, { method, headers: {
      ...(cookie ? { cookie } : {}), ...(body && !form ? { 'Content-Type': 'application/json' } : {}),
    }, body: form ? body : body ? JSON.stringify(body) : undefined });
    return { status: response.status, cookie: response.headers.get('set-cookie')?.split(';')[0],
      body: (response.status === 204 ? null : await response.json()) as T };
  };
  const alpha = { username: 'manager', name: '测试管理员', password: 'local-test-password' };
  const beta = { username: 'member', name: '测试成员', password: 'local-member-password' };
  const { cookie, status } = await request('/auth/setup', { method: 'POST', body: alpha });
  assert.equal(status, 200);
  const user = <T = unknown>(path: string, method?: string, body?: object) => request<T>(path, { method, body, cookie });
  assert.equal((await user('/users', 'POST', beta)).status, 201);
  const { cookie: member } = await request('/auth/login', { method: 'POST', body: beta });
  assert.equal((await request('/users', { cookie: member })).status, 403);
  assert.deepEqual((await request('/chats', { cookie: member })).body, []);
  const skillForm = new FormData();
  const zip = new AdmZip();
  zip.addFile('fixture/SKILL.md', Buffer.from('---\nname: qa-skill\ndescription: QA test\n---\nSKILL-TEST-SIGNAL'));
  zip.addFile('fixture/references/example.md', Buffer.from('test reference'));
  skillForm.append('file', new Blob([Uint8Array.from(zip.toBuffer())]), 'skill.zip');
  const skill = await user<Skill>('/skills', 'POST', skillForm);
  assert.equal(skill.status, 201, JSON.stringify(skill.body));
  const chat = (await user<{ id: string }>('/chats', 'POST', {})).body.id;
  const oldWorkspace = join(folder, 'data', 'workspaces', chat, '历史目录');
  await mkdir(oldWorkspace, { recursive: true });
  await writeFile(join(oldWorkspace, '旧笔记.md'), '已有工作区正文');
  const uploadForm = new FormData();
  uploadForm.append('file', new Blob(['客户提供的会议材料']), '会议材料.md');
  const uploaded = await user<DocumentRecord>('/documents', 'POST', uploadForm);
  assert.equal(uploaded.status, 201);
  assert.equal(uploaded.body.name, '会议材料.md');
  assert.equal((await request('/documents/' + uploaded.body.id + '/preview', { cookie: member })).status, 404);
  assert.equal((await request('/documents/' + uploaded.body.id + '/download', { cookie: member })).status, 404);
  assert.equal((await request('/chats/' + chat, { cookie: member })).status, 404);
  assert.equal((await request<Skill[]>('/skills', { cookie: member })).body.length, 0);
  const memberChat = (await request<{ id: string }>('/chats', { cookie: member, method: 'POST', body: {} })).body.id;
  assert.equal((await request('/chats/' + memberChat + '/runs', { cookie: member, method: 'POST',
    body: { prompt: '无权引用', skills: [skill.body.id] } })).status, 400);
  assert.equal((await request('/chats/' + memberChat + '/runs', { cookie: member, method: 'POST',
    body: { prompt: '无权引用附件', documents: [uploaded.body.id] } })).status, 404);
  async function answer(chat: string, id: string) {
    return eventually(async () => {
      const row = (await user<{ runs: ChatRun[] }>('/chats/' + chat)).body.runs.find((item) => item.id === id);
      if (row?.state === 'failed') throw new Error(row.error ?? '执行失败');
      return row?.state === 'done' && row;
    });
  }
  const first = await user<{ id: string }>('/chats/' + chat + '/runs', 'POST', {
    prompt: '引用 Skill', skills: [skill.body.id], documents: [uploaded.body.id],
  });
  const firstAnswer = await answer(chat, first.body.id);
  assert.equal(firstAnswer.answer, '收到 Skill 指令。');
  assert.equal(firstAnswer.attachments[0].id, uploaded.body.id);
  assert.equal((await user<{ id: string }>('/chats/' + chat + '/runs', 'POST', { prompt: '重复', requestId: first.body.id })).status, 202);
  const saved = await user<{ id: string }>('/chats/' + chat + '/runs', 'POST', { prompt: '写文件' });
  const savedAnswer = await answer(chat, saved.body.id);
  assert.ok(savedAnswer.tool_log.some((item) => item.name === 'workspace_write' && item.state === 'done'));
  const savedTool = savedAnswer.tool_log.find((item) => item.name === 'workspace_write');
  assert.match(savedTool?.input ?? '', /验收\.txt/);
  assert.match(savedTool?.output ?? '', /"saved": true/);
  assert.ok(savedAnswer.progress_log.some((item) => item.title === '确定下一步'));
  assert.equal(savedAnswer.documents[0].name, '验收.txt');
  const wordRun = await user<{ id: string }>('/chats/' + chat + '/runs', 'POST', { prompt: '生成验收 Word' });
  const word = (await answer(chat, wordRun.body.id)).documents[0];
  assert.equal(word.name, '验收报告.docx');
  const preview = (await user<DocumentPreview>('/documents/' + word.id + '/preview')).body;
  assert.ok('text' in preview && preview.text.includes('文档生成成功。'));
  const download = await fetch(base + '/api/documents/' + word.id + '/download', { headers: { cookie: cookie ?? '' } });
  assert.equal(download.status, 200);
  assert.match(download.headers.get('content-disposition') ?? '', /^attachment/);
  const wordBytes = Buffer.from(await download.arrayBuffer());
  assert.ok(new AdmZip(wordBytes).getEntry('word/document.xml'));
  const library = (await user<{ rows: DocumentRecord[] }>('/documents')).body.rows;
  assert.ok(library.find((item) => item.id === uploaded.body.id && item.source === 'upload'));
  assert.ok(library.find((item) => item.id === word.id && item.run_id === wordRun.body.id));
  assert.ok(library.find((item) => item.name === '旧笔记.md' && item.source === 'workspace'));
  const physicalFiles = await readdir(join(folder, 'data', 'documents'), { recursive: true, withFileTypes: true });
  assert.equal(physicalFiles.filter((item) => item.isFile()).length, library.length);
  assert.equal((await user<{ content: string }>('/chats/' + chat + '/file?path=' + encodeURIComponent('结果/验收.txt'))).body.content, '工具调用成功');
  assert.equal((await user('/chats/' + chat + '/file?path=../credentials.key')).status, 400);
  assert.equal((await user('/chats/' + chat + '/file?path=..%5Ccredentials.key')).status, 400);
  assert.equal((await user('/chats/' + chat + '/file?path=credentials.txt%3Astream')).status, 400);
  const schedule = await user<{ id: string }>('/schedules', 'POST', { name: '验收任务', prompt: '引用 Skill', skills: [skill.body.id], cron: '* * * * *', enabled: true });
  assert.equal(schedule.status, 200, JSON.stringify(schedule.body));
  const db = new DatabaseSync(join(folder, 'data', 'workbench.sqlite'));
  db.prepare('UPDATE schedules SET next=? WHERE id=?').run(Date.now() - 1000, schedule.body.id);
  db.close();
  const task = await eventually(async () => (await user<Schedule[]>('/schedules')).body.find((item) => item.last_state === 'done'));
  assert.equal((await user<{ runs: ChatRun[] }>('/chats/' + task.last_chat)).body.runs[0].answer, '收到 Skill 指令。');
  for (const platform of ['wecom', 'feishu', 'dingtalk']) {
    const result = await user<Bot>('/bots', 'POST', { name: platform, platform, mode: 'bot',
      secrets: platform === 'wecom' ? { botSecret: 'placeholder' } : { appSecret: 'placeholder' } });
    assert.equal(result.status, 200, JSON.stringify(result.body));
    assert.equal(result.body.status, 'offline');
    assert.equal(JSON.stringify(result.body).includes('placeholder'), false);
  }
  await stop();
  await start();
  assert.equal((await user<{ runs: ChatRun[] }>('/chats/' + chat)).body.runs.length, 3);
  const continued = await user<{ id: string }>('/chats/' + chat + '/runs', 'POST', { prompt: '继续对话' });
  assert.equal((await answer(chat, continued.body.id)).answer, '本地模型回复。');
  assert.equal((await user('/bots', 'POST', { name: '飞书指挥机器人', platform: 'feishu', mode: 'bot',
    enabled: true, secrets: { appId: 'fixture-app', appSecret: 'fixture-app-secret' } })).status, 200);
  const profile = { name: '飞书本人接管', platform: 'feishu', mode: 'personal',
    settings: { employeeId: 'ou_fixture', groupMentionName: '飞书本人', targets: [{ id: 'oc_fixture', group: true }] },
    secrets: { appId: 'fixture-app', appSecret: 'fixture-app-secret' } };
  const bot = await user<Bot>('/bots', 'POST', profile);
  assert.equal(bot.status, 200);
  const auth = await user<{ url: string }>('/bots/' + bot.body.id + '/authorize', 'POST');
  assert.equal(auth.status, 200, JSON.stringify(auth.body));
  const state = new URL(auth.body.url).searchParams.get('state');
  assert.ok(state);
  const callback = base + '/integrations/feishu/callback?' + new URLSearchParams({ code: 'fixture-code', state });
  assert.equal((await fetch(callback)).status, 200);
  assert.equal((await fetch(callback)).status, 400);
  const configurationPath = '/bots/' + bot.body.id + '/configuration';
  const editable = (await user<BotConfiguration>(configurationPath)).body;
  assert.deepEqual(editable.secrets, { ...profile.secrets, userToken: 'employee-fixture' });
  assert.equal(editable.settings.groupMentionName, profile.settings.groupMentionName);
  assert.equal((await request(configurationPath, { cookie: member })).status, 404);
  const unchangedBot = (await user<Bot>('/bots/' + bot.body.id, 'PUT', { ...profile, secrets: editable.secrets })).body;
  assert.equal(unchangedBot.version, editable.version);
  const enabled = await user('/bots/' + bot.body.id, 'PUT', { ...profile, enabled: true, secrets: editable.secrets });
  assert.equal(enabled.status, 200);
  await sleep(40);
  const inbound = { chat_id: 'oc_fixture', create_time: String(Date.now()), sender: { id: 'ou_colleague', sender_type: 'user' },
    msg_type: 'text', body: { content: JSON.stringify({ text: '创建飞书文档' }) } };
  incoming.push({ ...inbound, message_id: 'om_unmentioned' });
  const mentioned = { ...inbound, message_id: 'om_request', mentions: [{ id: 'ou_fixture' }] };
  incoming.push(mentioned, mentioned);
  const feishuTextMention = '其他来信也请给草稿 @飞书本人';
  const fallback = { ...inbound, message_id: 'om_other_request', body: { content: JSON.stringify({ text: feishuTextMention }) } };
  incoming.push(fallback, { ...fallback, message_id: 'om_other_member', mentions: [{ id: 'ou_colleague' }] });
  const waitingFeishu = await eventually(async () => {
    const rows = (await user<{ rows: ReplyRecord[] }>('/replies?state=review')).body.rows;
    return rows.length === 2 && rows.every((row) => row.review_state === 'ready') && rows;
  }, 28000);
  assert.equal(written.length, 0);
  assert.equal(delivered.length, 0);
  const feishuNotice = notices.find((notice) => notice.text.includes('创建飞书文档'));
  const original = waitingFeishu.find((row) => row.prompt === '创建飞书文档');
  assert.ok(feishuNotice && original);
  assert.equal((await user('/replies/' + original.id + '/decision', 'POST', { action: 'approve' })).status, 409);
  const feishuCommand = (id: string, text: string, parent: string) => botEvents.feishu.push({
    sender: { sender_id: { open_id: 'ou_fixture' }, sender_type: 'user' },
    message: { chat_id: 'oc_robot_fixture', chat_type: 'p2p', message_type: 'text', content: JSON.stringify({ text }),
      parent_id: parent, create_time: String(Date.now()), message_id: id },
  });
  feishuCommand('om_owner_review', '按这个发，用一句话就好', feishuNotice.id);
  const results = await eventually(async () => {
    const response = await user<BotActivity[]>('/bots/' + bot.body.id + '/activity');
    return response.body.some((row) => row.reply === 'accepted') && response.body;
  }, 22000);
  assert.equal(results.length, 3);
  const sentFeishu = results.find((row) => row.reply === 'accepted');
  assert.equal(sentFeishu?.receipt, 'om_reply');
  const feishuRuns = (await user<{ runs: ChatRun[] }>('/chats/' + original.chat)).body.runs;
  const feishuTools = feishuRuns.find((row) => row.id === original.id)?.tool_log ?? [];
  for (const name of ['cloud_document_search', 'cloud_document', 'reply_document']) {
    assert.ok(feishuTools.some((item) => item.name === name && item.state === 'done'));
  }
  assert.deepEqual(written, ['验收内容']);
  assert.equal(delivered.length, 1);
  assert.equal(delivered[0].receive_id, 'oc_fixture');
  assert.equal(JSON.parse(delivered[0].content).text, '请查看验收安排。\n\nhttps://fixture.feishu.cn/docx/mock_doc');
  assert.equal(delivered[0].uuid, sentFeishu?.id);
  assert.ok(feishuRuns.find((row) => row.id === sentFeishu?.id)?.tool_log.some((tool) => tool.name === 'wecom_reply_decision' && tool.state === 'done'));
  assert.equal(feishuRuns.find((row) => row.prompt === feishuTextMention)?.reply, 'awaiting_review');
  await eventually(() => notices.some((notice) => notice.text.includes('已回复原会话')));
  const otherFeishu = waitingFeishu.find((row) => row.prompt === feishuTextMention);
  const otherNotice = notices.find((notice) => notice.text.includes('其他来信也请给草稿'));
  assert.ok(otherFeishu && otherNotice);
  feishuCommand('om_owner_approve_exact', '按这个发送。', otherNotice.id);
  const plainApproval = await eventually(async () => {
    const row = (await user<{ runs: ChatRun[] }>('/chats/' + original.chat)).body.runs
      .find((run) => run.id !== otherFeishu.id && run.prompt === otherFeishu.prompt && run.reply === 'accepted');
    return row;
  }, 22000);
  assert.equal(plainApproval.reply_text, otherFeishu.answer);
  assert.deepEqual(plainApproval.tool_log, []);
  const dingRobot = await user<Bot>('/bots', 'POST', { name: '钉钉指挥机器人', platform: 'dingtalk', mode: 'bot', enabled: true,
    settings: { targets: [{ id: 'ding-staff', group: false }] }, secrets: { appId: 'fixture-ding-app', appSecret: 'fixture-ding-app-secret' } });
  assert.equal(dingRobot.status, 200);
  const ding = await user<Bot>('/bots', 'POST', { name: '钉钉本人接管', platform: 'dingtalk', mode: 'personal', enabled: true,
    settings: { employeeId: 'ding-owner', reviewBotId: dingRobot.body.id, reviewUserId: 'ding-staff', groupMentionName: '钉钉本人',
      targets: [{ id: 'ding-colleague-chat', group: false }, { id: 'ding-group-chat', group: true }] },
    secrets: { userToken: 'employee-ding-token' } });
  assert.equal(ding.status, 200);
  await sleep(40);
  dingIncoming.push({ openConversationId: 'ding-colleague-chat', openMessageId: 'ding-inbound', createTime: Date.now(), sendType: 'user',
    senderOpenDingTalkId: 'ding-colleague', content: '钉钉验收测试问题' });
  const dingDraft = await eventually(async () => (await user<ReplyPage>('/replies?botId=' + ding.body.id)).body.rows
    .find((row) => row.review_state === 'ready'), 28000);
  assert.equal(dingDelivered.length, 0);
  assert.ok(dingNotices.some((item) => item.target === 'ding-staff' && item.text.includes(dingDraft.id.slice(0, 8))));
  const dingCommand = { conversationType: '1', msgtype: 'text', robotCode: 'fixture-ding-app', msgId: 'ding-approve',
    senderStaffId: 'ding-staff', senderCorpId: 'fixture-org', chatbotCorpId: 'fixture-org', text: { content: '按这个发' }, createAt: Date.now() };
  botEvents.dingtalk.push({ ...dingCommand, msgId: 'ding-untrusted', senderCorpId: 'other-org' }, dingCommand, dingCommand);
  await eventually(() => dingNotices.some((item) => item.target === 'ding-staff' && item.text.includes('已回复原会话')), 15000);
  assert.deepEqual(dingDelivered, [{ token: 'employee-ding-token', target: 'ding-colleague-chat', text: dingDraft.answer }]);
  const dingGroupText = '确认群里的验收时间。＠钉钉本人';
  dingIncoming.push({ openConversationId: 'ding-group-chat', openMessageId: 'ding-group-inbound', createTime: Date.now(), sendType: 'user',
    senderOpenDingTalkId: 'ding-colleague', content: dingGroupText });
  const dingGroupDraft = await eventually(async () => (await user<ReplyPage>('/replies?botId=' + ding.body.id)).body.rows
    .find((row) => row.review_state === 'ready' && row.prompt === dingGroupText), 28000);
  assert.equal(dingGroupDraft.target, 'ding-group-chat');
  assert.equal(dingDelivered.length, 1);
  const wecomRobot = await user<Bot>('/bots', 'POST', { name: '企微指挥机器人', platform: 'wecom', mode: 'bot',
    enabled: true, settings: { targets: [{ id: 'wecom_staff_fixture', group: false }] },
    secrets: { botId: 'wecom-application', botSecret: 'robot-fixture' } });
  assert.equal(wecomRobot.status, 200);
  const wecomBot = await user<Bot>('/bots', 'POST', {
    name: '企微接管验收', platform: 'wecom', mode: 'personal', enabled: true,
    settings: { employeeId: 'employee_fixture', reviewBotId: wecomRobot.body.id, reviewUserId: 'wecom_staff_fixture', groupMentionName: 'TigerLand',
      targets: [{ id: 'colleague_fixture', group: false }, { id: 'group_fixture', group: true }] },
    secrets: { messageMcp: 'https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture',
      documentMcp: 'https://qyapi.weixin.qq.com/mcp/v2/bot/doc?apikey=fixture' },
  });
  assert.equal(wecomBot.status, 200);
  const urlPath = '/bots/' + wecomBot.body.id + '/mcp-urls';
  assert.deepEqual((await user(urlPath)).body, {
    messageMcp: 'https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture',
    documentMcp: 'https://qyapi.weixin.qq.com/mcp/v2/bot/doc?apikey=fixture',
  });
  assert.equal((await request(urlPath, { cookie: member })).status, 404);
  assert.equal(JSON.stringify((await user('/bots')).body).includes('apikey=fixture'), false);
  await sleep(1100);
  wecom.receive('group_fixture', { id: 'ignored', sender: 'colleague_fixture', text: '群里未点名消息' });
  wecom.receive('group_fixture', { id: 'partial-name', sender: 'colleague_fixture', text: '请 @TigerLand-2 核对验收安排' });
  wecom.receive('auto_group_fixture', { id: 'auto-ignored', sender: 'colleague_fixture', text: '请 @TigerLand 核对验收安排' });
  const localAttachment = new AdmZip();
  localAttachment.addFile('word/document.xml', Buffer.from('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>RPL-821 现场验收记录</w:t></w:r></w:p></w:body></w:document>'));
  wecom.receiveFile('group_fixture', 'colleague_fixture', 'local_file', '现场核对.docx', localAttachment.toBuffer());
  wecom.receive('group_fixture', { id: 'document', sender: 'colleague_fixture',
    text: '请根据本地 Word 和 https://doc.weixin.qq.com/doc/w_fixture_doc 整理交付报告，回传 Word 附件和企微在线文档链接。@TigerLand' });
  wecom.receive('colleague_fixture', { id: 'private', sender: 'colleague_fixture',
    text: '正式验收改到几点？帮我整理成云文档' });
  const drafts = await eventually(async () => {
    const response = await user<{ rows: ReplyRecord[] }>('/replies?state=review&botId=' + wecomBot.body.id);
    if (response.status !== 200) throw new Error(JSON.stringify(response));
    return response.body.rows.length === 2 && response.body.rows.every((row) => row.review_state === 'ready') && response.body.rows;
  }, 35000);
  assert.deepEqual(wecom.opened, ['w_fixture_doc']);
  assert.deepEqual(wecom.searched, [['验收']]);
  assert.deepEqual(wecom.sheetRanges, [{ docid: 'search_fixture_sheet', sheet_id: 'fixture_subsheet', range: 'A7:J17', mode: 'csv' }]);
  assert.equal(wecom.created.length, 0);
  assert.equal(wecom.sent.length, 0);
  assert.equal(wecomNotices.length, 2);
  assert.ok(wecomNotices.every((item) => item.target === 'wecom_staff_fixture'));
  const groupDraft = drafts.find((row) => row.target === 'group_fixture');
  const privateDraft = drafts.find((row) => row.target === 'colleague_fixture');
  assert.ok(groupDraft && privateDraft);
  const selectedBot = await user<ReplyPage>('/replies?' + new URLSearchParams({
    botId: wecomBot.body.id, state: 'review', q: '正式验收',
  }));
  assert.equal(selectedBot.body.total, 1);
  assert.deepEqual(selectedBot.body.rows.map((row) => row.id), [privateDraft.id]);
  assert.ok(selectedBot.body.bots.some((item) => item.id === bot.body.id));
  const allBots = await user<ReplyPage>('/replies?botId=all');
  assert.ok(allBots.body.rows.some((row) => row.bot === bot.body.id));
  assert.ok(allBots.body.rows.some((row) => row.bot === wecomBot.body.id));
  const memberBotFilter = await request<ReplyPage>('/replies?botId=' + wecomBot.body.id, { cookie: member });
  assert.equal(memberBotFilter.body.total, 0);
  assert.equal(memberBotFilter.body.bots.length, 0);
  assert.ok(groupDraft.draft?.includes('周五交付'));
  assert.ok(privateDraft.draft?.includes('周三 14:30'), JSON.stringify({ draft: privateDraft.draft,
    tools: (await user<{ runs: ChatRun[] }>('/chats/' + privateDraft.chat)).body.runs[0].tool_log }));
  assert.ok(Buffer.byteLength(privateDraft.draft ?? '') > 2048);
  const privateNotice = wecomNotices.find((item) => item.text.includes('接管 ' + privateDraft.id.slice(0, 8)));
  assert.ok(privateNotice && Buffer.byteLength(privateNotice.text) <= 2000 && privateNotice.text.includes('…'));
  const previewDraft = await user<ReplyRecord>('/replies/' + groupDraft.id);
  assert.ok(previewDraft.body.document?.content.includes('项目将于周五交付'));
  assert.equal(previewDraft.body.document?.url, null);
  assert.ok(wecomNotices.some((item) => item.text.includes('接管 ' + groupDraft.id.slice(0, 8))));
  const decision = '/replies/' + groupDraft.id + '/decision';
  assert.equal((await request(decision, { method: 'POST', body: { action: 'approve' }, cookie: member })).status, 404);
  assert.equal((await request<{ total: number }>('/replies', { cookie: member })).body.total, 0);
  assert.equal((await user(decision, 'POST', { action: 'approve' })).status, 409);
  await stop();
  await start();
  assert.equal((await user<ReplyRecord>('/replies/' + groupDraft.id)).body.review_state, 'ready');
  const owner = 'wecom_staff_fixture';
  const mcpOwner = 'employee_fixture';
  const wecomCommand = ({ id, sender, text }: { id: string; sender: string; text: string }, group = false) =>
    botEvents.wecom.push({ aibotid: 'wecom-application', msgid: id, from: { userid: sender },
      chattype: group ? 'group' : 'single', chatid: group ? 'group_fixture' : undefined, text: { content: text }, create_time: Date.now() });
  const detail = '交付明细已核对。'.repeat(110);
  const directReply = { id: 'direct-group', sender: owner, text: [
    '# 已核对，项目周五交付。', '- **验收时间** 14:30', '- 查看[原表](https://example.test/plan)',
    '', detail, '', detail, '', '全文结束。',
  ].join('\n') };
  const expectedParts = splitWecomReply(wecomPlainText(directReply.text), 'https://doc.weixin.qq.com/doc/created_fixture_doc');
  assert.ok(expectedParts.length >= 3);
  assert.ok(expectedParts.every((part) => Buffer.byteLength(part) <= 2000));
  assert.match(expectedParts.join('\n'), /• 验收时间 14:30/);
  assert.match(expectedParts.join('\n'), /原表 https:\/\/example.test\/plan/);
  assert.doesNotMatch(expectedParts.join('\n'), /\*\*|\[原表\]/);
  wecomCommand({ id: 'forged', sender: 'colleague_fixture', text: '接管 ' + groupDraft.id.slice(0, 8) });
  wecomCommand({ id: 'mcp-id-is-not-bot-id', sender: mcpOwner, text: '忽略 ' + groupDraft.id.slice(0, 8) });
  wecomCommand({ id: 'group-command', sender: owner, text: '忽略 ' + groupDraft.id.slice(0, 8) }, true);
  wecom.receive(mcpOwner, { id: 'old-self-command', sender: mcpOwner, text: '忽略 ' + groupDraft.id.slice(0, 8) });
  wecomCommand({ ...directReply, id: 'ambiguous' });
  wecomCommand({ id: 'list', sender: owner, text: '接管待办' });
  wecomCommand({ id: 'command-private', sender: owner, text: '接管 ' + privateDraft.id.slice(0, 8) + ' 此条无需回复' });
  await eventually(() => wecomNotices.some((item) => item.target === owner && item.text.includes('Pi 判断无需回复')), 26000);
  assert.ok(wecomNotices.some((item) => item.text.includes('核对来信')));
  assert.equal(wecom.sent.filter((item) => item.target !== mcpOwner).length, 0);
  wecomCommand({ id: 'unrelated', sender: owner, text: '晚上记得买牛奶' });
  await eventually(() => wecomNotices.some((item) => item.target === owner && item.text.includes('原来信仍在待办里')), 26000);
  assert.equal((await user<ReplyRecord>('/replies/' + groupDraft.id)).body.review_state, 'ready');
  wecom.rejectNext.set('group_fixture', '【2/');
  wecomCommand(directReply);
  wecomCommand(directReply);
  await eventually(() => wecomNotices.some((item) => item.target === owner && item.text.includes('企微未受理发送')), 30000);
  assert.deepEqual(wecom.sent.filter((item) => item.target !== mcpOwner).map((item) => item.text), expectedParts.slice(0, 1));
  wecomCommand({ id: 'retry-group', sender: owner, text: '重试 ' + groupDraft.id.slice(0, 8) });
  await eventually(() => wecom.sent.filter((item) => item.target === 'group_fixture').length === expectedParts.length, 30000);
  await eventually(() => wecomNotices.some((item) => item.target === owner && item.text.includes('已回复原会话') && item.text.includes('原生附件未发送')), 10000);
  assert.equal(wecom.deliveredFiles.length, 0);
  assert.equal(wecom.uploaded.length, 0);
  const finalText = expectedParts.join('\n\n');
  assert.deepEqual(wecom.sent.filter((item) => item.target !== mcpOwner).map((item) => item.text), expectedParts);
  assert.ok(wecomNotices.some((item) => item.text.includes('接管待办') && item.text.includes(groupDraft.id.slice(0, 8))));
  assert.equal(wecomNotices.filter((item) => item.text.includes('已开始处理 ' + groupDraft.id.slice(0, 8))).length, 2);
  assert.ok(wecom.sent.every((item) => item.target !== mcpOwner));
  assert.equal(wecom.created.length, 1);
  assert.ok(wecom.created[0].content.includes('项目将于周五交付'));
  const sent = await eventually(async () => {
    const rows = (await user<{ rows: ReplyRecord[] }>('/replies?state=accepted&q=' + encodeURIComponent('已核对'))).body.rows;
    return rows.find((row) => row.target === 'group_fixture');
  });
  assert.equal(sent.reply_text, finalText);
  assert.ok(sent.sent_at);
  const sentRun = (await user<{ runs: ChatRun[] }>('/chats/' + sent.chat)).body.runs.find((row) => row.id === sent.id);
  assert.equal(sentRun?.attachments[0].name, '现场核对.docx');
  const generated = sentRun?.documents.find((item) => item.name === '回传成品.docx');
  assert.ok(generated);
  const filePreview = (await user<DocumentPreview>('/documents/' + generated.id + '/preview')).body;
  assert.ok('text' in filePreview && filePreview.text.includes('RPL-821'));
  assert.equal(sentRun?.tool_log.filter((tool) => tool.name === 'wecom_reply_decision' && tool.state === 'done').length, 1);
  assert.equal((await user<ReplyRecord>('/replies/' + privateDraft.id)).body.review_state, 'commanded');

  const findReply = async (state: string, prompt: string) => {
    const result = await user<{ rows: ReplyRecord[] }>('/replies?' + new URLSearchParams({ state, q: prompt }));
    assert.equal(result.status, 200);
    return result.body.rows.find((row) => row.prompt === prompt);
  };
  const skip = '收到，谢谢。无需跟进。';
  const hold = '请核对取消测试中的验收安排';
  const next = '请核对取消之后新增的事项';
  const parallel = '请处理另一会话中的新增申请';
  wecom.receive('colleague_fixture', { id: 'triage-skip', sender: 'colleague_fixture', text: skip });
  wecom.receive('colleague_fixture', { id: 'cancel-active', sender: 'colleague_fixture', text: hold });
  const skipped = await eventually(() => findReply('skipped', skip), 35000);
  assert.equal(skipped.answer, '纯确认和感谢，无需跟进。');
  assert.equal(skipped.can_cancel, 0);
  assert.ok(!wecom.sent.some((item) => item.text.includes(skip)));
  await eventually(() => held.started);
  const processing = await eventually(() => findReply('pending', hold));
  wecom.receive('group_fixture', { id: 'parallel-other-chat', sender: 'colleague_fixture', text: parallel, at: [mcpOwner] });
  const other = await eventually(async () => {
    const row = await findReply('review', parallel);
    return row?.review_state === 'ready' ? row : undefined;
  }, 35000);
  assert.notEqual(other.chat, processing.chat);
  assert.equal(held.aborted, false);
  const cancel = '/replies/' + processing.id + '/cancel';
  assert.equal(processing.can_cancel, 1);
  assert.equal((await request(cancel, { method: 'POST', cookie: member })).status, 404);
  assert.equal((await user(cancel, 'POST')).status, 204);
  assert.equal((await user(cancel, 'POST')).status, 204);
  await eventually(() => held.aborted);
  const canceled = (await user<ReplyRecord>('/replies/' + processing.id)).body;
  assert.equal(canceled.reply, 'canceled');
  assert.equal(canceled.can_cancel, 0);
  assert.equal(canceled.error, '已手动取消本次处理');
  assert.equal((await user<ReplyRecord>('/replies/' + other.id)).body.review_state, 'ready');
  wecom.receive('colleague_fixture', { id: 'continue-after-cancel', sender: 'colleague_fixture', text: next });
  const nextReply = await eventually(async () => {
    const row = await findReply('review', next);
    return row?.review_state === 'ready' ? row : undefined;
  }, 35000);
  assert.equal(nextReply.chat, canceled.chat);
  assert.ok(!wecom.sent.some((item) => item.text.includes(hold)));
  assert.equal((await user('/replies/' + nextReply.id + '/cancel', 'POST')).status, 204);
  assert.equal((await user<ReplyRecord>('/replies/' + nextReply.id)).body.review_state, 'canceled');
  assert.equal((await user('/replies/' + other.id + '/cancel', 'POST')).status, 204);
  wecomProactiveLimited = true;
  const limitedPrompt = '请确认这条主动通知限流测试';
  wecom.receive('colleague_fixture', { id: 'proactive-limited', sender: 'colleague_fixture', text: limitedPrompt });
  const heldNotice = await eventually(async () => {
    const reply = await findReply('review', limitedPrompt);
    return reply?.review_state === 'failed' ? reply : undefined;
  }, 35000);
  wecomCommand({ id: 'request-held-notice', sender: owner, text: '接管待办' });
  await eventually(() => wecomNotices.some((item) => item.text.includes('接管待办 ' + heldNotice.id.slice(0, 8)) && item.text.includes(limitedPrompt)));
  assert.equal((await user<ReplyRecord>('/replies/' + heldNotice.id)).body.review_state, 'ready');
});
