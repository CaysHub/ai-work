import type { Server } from 'node:http';

type ModelInput = { messages: { role: string; content: string }[]; stream: boolean; model: string;
  tool_choice?: string | { function?: { name?: string } } };
export async function mockModel(server: Server) {
  const held = { started: false, aborted: false };
  server.on('request', async (request, response) => {
    if (request.url?.startsWith('/feishu/') || request.url?.startsWith('/wecom/')) return;
    let payload = '';
    for await (const chunk of request) payload += chunk;
    if (request.url !== '/v1/chat/completions' || request.headers.authorization !== 'Bearer local-test-only') {
      response.writeHead(404).end();
      return;
    }
    const input = JSON.parse(payload) as ModelInput;
    const message = input.messages?.at(-1);
    if (!message || !input.stream) {
      response.writeHead(400).end();
      return;
    }
    const writes = message.role === 'user' && message.content.includes('写文件');
    const writesWord = message.role === 'user' && message.content.includes('生成验收 Word');
    const creates = message.role === 'user' && message.content.includes('创建飞书文档');
    const documentRun = input.messages.some((item) => item.role === 'user' && item.content.includes('创建飞书文档'));
    const searchWecom = message.role === 'user' && message.content.includes('正式验收改到几点');
    const searchFeishu = creates;
    const foundWecom = message.role === 'tool' && message.content.includes('"docs"') && message.content.includes('search_fixture_sheet');
    const sheetMetadata = message.role === 'tool' && message.content.includes('fixture_subsheet');
    const foundFeishu = message.role === 'tool' && message.content.includes('"documents"') && message.content.includes('feishu_source_doc');
    const readWecom = message.role === 'tool' && message.content.includes('"46351.6041666667":"2026-11-25 14:30"');
    const readFeishu = message.role === 'tool' && message.content.includes('飞书验收资料正文');
    const sourceLink = message.role === 'user' && message.content.includes('云文档测试正文');
    const stagedDocument = message.role === 'tool' && message.content.includes('"staged":true');
    const receivedFile = message.role === 'user' && /"id":"([0-9a-f-]+)","name":"现场核对\.docx"/.exec(message.content);
    const readLocal = message.role === 'tool' && message.content.includes('RPL-821');
    const generatedLocal = message.role === 'tool' && message.content.includes('回传成品.docx');
    const command = input.messages.filter((item) => item.role === 'system').map((item) => item.content).join('\n');
    const ownerDecision = command.includes('本人补充要求');
    if (ownerDecision && command.includes('按这个发，用一句话就好') &&
        (!command.includes('oc_fixture') || !command.includes('完整草稿') || !command.includes('请查看验收安排。'))) {
      response.writeHead(400).end();
      return;
    }
    const triage = typeof input.tool_choice === 'object' && input.tool_choice.function?.name === 'im_reply_triage';
    if (!triage && message.role === 'user' && message.content === '请核对取消测试中的验收安排') {
      held.started = true;
      response.on('close', () => { held.aborted = !response.writableEnded; });
      return;
    }
    const recovery = message.role === 'user' && /^根据已查证的资料提交这条(?:企微|飞书)来信的处理决定。$/.test(message.content);
    if (recovery && (typeof input.tool_choice !== 'object' || input.tool_choice.function?.name !== 'wecom_reply_decision')) {
      response.writeHead(400).end();
      return;
    }
    const action = triage ? { name: 'im_reply_triage', arguments: JSON.parse(message.content).message === '收到，谢谢。无需跟进。'
      ? { action: 'skip', reason: '纯确认和感谢，无需跟进。' } : { action: 'process', reason: '继续核对来信。' } } :
      recovery ?
      { name: 'wecom_reply_decision', arguments: { action: 'reply' } } :
      ownerDecision && command.includes('晚上记得买牛奶') && message.role === 'user' ?
      { name: 'wecom_reply_decision', arguments: { action: 'keep', text: '这句话与项目来信无关。' } } :
      ownerDecision && command.includes('无需回复') && message.role === 'user' ?
      { name: 'wecom_reply_decision', arguments: { action: 'skip', text: '本人要求无需回复。' } } :
      ownerDecision && stagedDocument && command.includes('按这个发，用一句话就好') ?
      { name: 'wecom_reply_decision', arguments: { action: 'compose', text: '请查看验收安排。' } } :
      receivedFile ? { name: 'attached_document_read', arguments: { documentId: receivedFile[1] } } :
      readLocal && ownerDecision ? { name: 'word_document_create', arguments: {
        path: '回传成品.docx', title: '交付报告', content: '已根据 RPL-821 核对现场附件。' } } :
      ownerDecision && stagedDocument && !command.includes('本人原文') ?
        { name: 'wecom_reply_decision', arguments: { action: 'reply',
          text: '已核对，项目周五交付。' } } :
      writesWord ? { name: 'word_document_create', arguments: {
      path: '结果/验收报告.docx', title: '交付验收', content: '# 概要\n文档生成成功。\n- 核对原始文件',
    } } : writes ? { name: 'workspace_write', arguments: { path: '结果/验收.txt', content: '工具调用成功' } } :
      searchWecom || searchFeishu ? { name: 'cloud_document_search', arguments: { keywords: ['验收'] } } :
      foundWecom || sheetMetadata ? { name: 'wecom_sheet_read', arguments: {
        documentId: 'search_fixture_sheet', ...(sheetMetadata ? { ranges: ['里程碑计划!A7:J17'] } : {}),
      } } : foundFeishu ? { name: 'cloud_document', arguments: {
        platform: 'feishu', action: 'read', documentId: 'feishu_source_doc',
      } } : readWecom || readFeishu || sourceLink || readLocal || generatedLocal ? { name: 'reply_document', arguments: {
        title: '验收安排', content: readFeishu ? '验收内容' : readWecom ? '# 验收安排\n周三 14:30 正式验收。' : '# 交付计划\n项目将于周五交付。',
      } } : null;
    const delta = action ? { role: 'assistant', tool_calls: [{
      index: 0, id: 'call_' + action.name + (sheetMetadata ? '_range' : '') + (ownerDecision ? '_command' : ''), type: 'function', function: { name: action.name, arguments: JSON.stringify(action.arguments) },
    }] } : { role: 'assistant', content: stagedDocument ?
      (documentRun ? '请查看验收安排。' : input.messages.some((item) => item.role === 'user' && item.content.includes('云文档测试正文'))
        ? '已读取云文档，项目将于周五交付。' : '查到正式验收已改为周三 14:30。' + '请核对里程碑计划。'.repeat(160)) :
      message.role === 'tool' ? '已保存验收文件。' :
        input.messages.some((item) => JSON.stringify(item.content).includes('SKILL-TEST-SIGNAL')) ? '收到 Skill 指令。' : '本地模型回复。' };
    const event = (choices: unknown[]) => 'data: ' + JSON.stringify({ id: 'local-completion', object: 'chat.completion.chunk',
      created: 1234, model: input.model, choices }) + '\n\n';
    response.writeHead(200, { 'Content-Type': 'text/event-stream' });
    response.write(event([{ index: 0, delta, finish_reason: null }]));
    response.write(event([{ index: 0, delta: {}, finish_reason: action ? 'tool_calls' : 'stop' }]));
    response.end('data: [DONE]\n\n');
  });
  return held;
}
