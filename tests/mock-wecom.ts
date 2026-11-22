import type { Server } from 'node:http';

type FixtureMessage = { msgid: string; userid: string; send_time: string; msg_type: string; mentioned_list: string[];
  text?: { content: string }; link?: { title: string; url: string }; file?: { media_id: string; file_name: string } };
type MessageInput = { id: string; sender: string; text: string; at?: string[]; type?: string; time?: number };
export function mockWecom(server: Server) {
  const incoming = new Map<string, FixtureMessage[]>();
  const sent: { target: string; text: string }[] = [];
  const opened: string[] = [];
  const searched: string[][] = [];
  const created: { doc_name: string; content: string }[] = [];
  const sheetRanges: { docid: string; sheet_id: string; range: string; mode: string }[] = [];
  const rejectNext = new Map<string, string>();
  const inputFiles = new Map<string, { file_name: string; base64_content: string }>();
  const uploaded: { file_name: string; base64_content: string }[] = [];
  const deliveredFiles: { chat_id: string; file: { media_id: string } }[] = [];
  const rejectFileOnce = new Set<string>();
  let rangePolls = 0;
  const clock = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium' });
  server.on('request', async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://localhost');
    if (!url.pathname.startsWith('/wecom/')) return;
    if (request.method !== 'POST') return response.writeHead(405).end();
    let raw = '';
    for await (const chunk of request) raw += chunk;
    const rpc = JSON.parse(raw);
    const send = (result: object) => response.writeHead(200, { 'Content-Type': 'application/json' })
      .end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
    if (rpc.method === 'initialize') return send({
      protocolVersion: rpc.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'wecom-fixture', version: '1' },
    });
    if (rpc.id === undefined) return response.writeHead(202).end();
    const { name, arguments: args } = rpc.params;
    function result(data: object) {
      return send({ content: [{ type: 'text', text: JSON.stringify({ errcode: 0, ...data }) }] });
    }
    if (name === 'chat_groups_list') {
      if (!args.cursor) return result({ chats: [], has_more: true, next_cursor: 'page-2' });
      return result({ chats: [{ chat_id: 'group_fixture', chat_name: '项目群' }], has_more: false });
    }
    if (name === 'chat_messages_list') return result({ has_more: false,
      messages: (incoming.get(args.chat_id) ?? []).filter((row) => row.send_time >= args.begin_time && row.send_time <= args.end_time) });
    if (name === 'message_send') {
      if (Buffer.byteLength(args.text.content) > 2048) return result({ errcode: 40058, errmsg: 'text exceeds byte budget' });
      const rejection = rejectNext.get(args.chat_id);
      if (rejection !== undefined && args.text.content.startsWith(rejection)) {
        rejectNext.delete(args.chat_id);
        return result({ errcode: 45009, errmsg: 'send temporarily rejected' });
      }
      sent.push({ target: args.chat_id, text: args.text.content });
      const msgid = 'wecom_sent_' + sent.length;
      if (args.chat_id !== 'employee_fixture') {
        const messages = incoming.get(args.chat_id) ?? [];
        messages.push({ msgid, userid: 'employee_fixture', send_time: clock.format(Date.now()),
          msg_type: 'text', mentioned_list: [], text: { content: args.text.content } });
        incoming.set(args.chat_id, messages);
      }
      return result({ msgid });
    }
    if (name === 'message_files_get' && inputFiles.has(args.media_id)) {
      const file = inputFiles.get(args.media_id)!;
      return result({ media_item: {
        media_id: args.media_id, file_name: file.file_name,
        file_path: { file_name: file.file_name, content: file.base64_content },
      } });
    }
    if (name === 'media_upload' && url.pathname.endsWith('/doc') && args.type === 'file') {
      uploaded.push(args);
      return result({ media_id: 'uploaded_fixture_file' });
    }
    if (name === 'message_aibot_send' && url.pathname.endsWith('/msg') && args.msg_type === 'file' && args.file.media_id === 'uploaded_fixture_file') {
      if (rejectFileOnce.delete(args.chat_id)) {
        return result({ errcode: 853008, errmsg: '当前会话不是机器人的最近会话' });
      }
      deliveredFiles.push(args);
      return result({ msgid: 'file_receipt' });
    }
    if (name === 'doc_contents_get_submit') {
      opened.push(args.docid);
      return result({ taskid: args.docid });
    }
    if (name === 'doc_contents_get_query') return result({ content: '云文档测试正文：项目将于周五交付。' });
    if (name === 'doc_search') {
      searched.push(args.keywords);
      return result({ docs: [{ docid: 'search_fixture_sheet', doc_name: '项目验收计划 V1.3', doc_type: 'sheet' }] });
    }
    if (name === 'sheet_get' && args.docid === 'search_fixture_sheet') {
      return result({ sheets: [{ sheet_id: 'fixture_subsheet', title: '里程碑计划', range: 'A1:J17' }] });
    }
    if (name === 'sheet_ranges_get_submit' && args.docid === 'search_fixture_sheet') {
      if (args.sheet_id !== 'fixture_subsheet' || args.range !== 'A7:J17' || args.mode !== 'csv') {
        return result({ errcode: 40001, errmsg: 'invalid sheet range parameters' });
      }
      sheetRanges.push(args);
      return result({ taskid: 'fixture_range_task' });
    }
    if (name === 'sheet_ranges_get_query' && args.taskid === 'fixture_range_task') {
      if (rangePolls++ === 0) return result({ long_task_status: { done: false } });
      return result({ content: 'M08,正式验收,46351.6041666667,总部 B3-301\r\n', long_task_status: { done: true } });
    }
    if (name === 'doc_create') {
      created.push(args);
      return result({ docid: 'created_fixture_doc', url: 'https://doc.weixin.qq.com/doc/created_fixture_doc' });
    }
    return result({ errcode: 40001, errmsg: 'unexpected tool' });
  });
  return {
    sent, opened, searched, created, sheetRanges, rejectNext, rejectFileOnce, uploaded, deliveredFiles,
    receiveFile(target: string, sender: string, id: string, name: string, bytes: Buffer) {
      inputFiles.set(id, { file_name: name, base64_content: bytes.toString('base64') });
      const messages = incoming.get(target) ?? [];
      messages.push({ msgid: id, userid: sender, send_time: clock.format(Date.now()),
        msg_type: 'file', mentioned_list: [], file: { media_id: id, file_name: name } });
      incoming.set(target, messages);
    },
    receive(target: string, { id, sender, text, at = [], type = 'text', time = Date.now() }: MessageInput) {
      const history = incoming.get(target) ?? [];
      history.push({ msgid: id, userid: sender, send_time: clock.format(time), msg_type: type, mentioned_list: at,
        ...(type === 'text' ? { text: { content: text } } : { link: { title: '验收文档', url: text } }) });
      incoming.set(target, history);
    },
  };
}
