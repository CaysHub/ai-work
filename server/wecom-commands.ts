import type { LiveBot, Transport } from './types.ts';
import type { HistoryMessage } from './platform.ts';
import { history, sendPersonal } from './platform.ts';
import { all, enqueue, get, run, transaction, unseal } from './store.ts';
import { now, parse } from './config.ts';
import { reconcileWecomParts, wecomReplyParts } from './wecom-delivery.ts';
import { reviewerTarget } from './replies.ts';

export const reviewWindow = 24 * 60 * 60 * 1000;
export type WecomDecision = 'reply' | 'skip' | 'keep';
export type WecomDecisionAction = WecomDecision | 'compose';

export function commandForRun(id: string) {
  return get<{ original: string; instruction: string; direct_text: string | null; decision: WecomDecision | null; text: string | null;
    originalPrompt: string; draft: string | null; target: string; title: string; documentTitle: string | null }>(
    `SELECT w.original,w.instruction,w.direct_text,w.decision,w.text,o.prompt AS originalPrompt,
      COALESCE(o.answer,v.draft) AS draft,c.target,c.title,d.title AS documentTitle
      FROM wecom_commands w JOIN runs o ON o.id=w.original JOIN chats c ON c.id=o.chat
      LEFT JOIN reply_reviews v ON v.id=o.id LEFT JOIN reply_documents d ON d.id=o.id WHERE w.id=?`, id);
}

export function decideWecomReply(id: string, action: WecomDecisionAction, text?: string) {
  const command = commandForRun(id);
  if (!command) throw new Error('没有收到本人接管指令');
  if (action !== 'reply' && action !== 'compose' && action !== 'skip' && action !== 'keep') throw new Error('无效的接管决定');
  const body = action === 'reply' && command.direct_text ? command.direct_text : text;
  const limit = action === 'reply' || action === 'compose' ? 24000 : 2000;
  if (typeof body !== 'string' || !body.trim() || Array.from(body).length > limit) {
    throw new Error('请提交回复正文或不回复的理由，最多 ' + limit + ' 字');
  }
  const decision = action === 'compose' ? 'reply' : action;
  run('UPDATE wecom_commands SET decision=?,text=? WHERE id=?', decision, body.trim(), id);
  return { action: decision, recorded: true };
}

export function restoreWecomPending(original: string) {
  if (!run(`UPDATE reply_reviews SET state=CASE WHEN notified IS NULL THEN 'unknown' ELSE 'ready' END, decided=NULL
    WHERE id=? AND state='commanded'`, original).changes) return;
  run("UPDATE runs SET reply='awaiting_review',updated=? WHERE id=? AND reply='canceled'", now(), original);
}

type Candidate = { id: string; title: string; prompt: string; chat: string; skills: string; transport: string; draft: string | null };
type Attempt = Candidate & { attempt: string; target: string; state: string; reply: string; updated: number;
  receipt: string | null; reply_text: string | null; instruction: string; direct_text: string | null };
const latestAttempt = `SELECT original.id, c.title,original.prompt,original.chat,original.skills,original.transport,original.answer AS draft,
  r.id AS attempt,c.target,r.state,r.reply,r.updated,r.receipt,r.reply_text,w.instruction,w.direct_text
  FROM wecom_commands w JOIN runs r ON r.id=w.id JOIN runs original ON original.id=w.original
  JOIN chats c ON c.id=original.chat
  WHERE c.bot=? AND c.version=? AND original.created>=?
  AND w.id=(SELECT a.id FROM wecom_commands a JOIN runs ar ON ar.id=a.id
    WHERE a.original=w.original ORDER BY ar.created DESC,ar.rowid DESC LIMIT 1)`;
const pending = `SELECT r.id,c.title,r.prompt,r.chat,r.skills,r.transport,v.draft FROM runs r
  JOIN chats c ON c.id=r.chat JOIN reply_reviews v ON v.id=r.id WHERE c.bot=? AND c.version=? AND
  r.created>=? AND r.reply='awaiting_review' AND v.state IN ('queued','notifying','ready','failed','unknown')`;

function queueAnalysis(item: Candidate, instruction: string, directText: string | null, approved?: string) {
  const transport = unseal<Transport>(item.transport);
  if (transport.channel !== 'personal') throw new Error('企微接管来信缺少会话信息');
  const child = enqueue(item.chat, item.prompt, parse(item.skills), { reply: 'waiting', transport });
  run('INSERT INTO wecom_commands (id,original,instruction,direct_text) VALUES (?,?,?,?)', child, item.id, instruction, directText);
  run("UPDATE reply_reviews SET state='commanded',decided=? WHERE id=?", now(), item.id);
  run("UPDATE runs SET reply='canceled',updated=? WHERE id=?", now(), item.id);
  if (approved) {
    run("UPDATE wecom_commands SET decision='reply',text=? WHERE id=?", approved, child);
    run("UPDATE runs SET state='done',reply='pending',answer=?,updated=? WHERE id=?", approved, now(), child);
    run(`INSERT INTO reply_documents (id,title,content)
      SELECT ?,title,content FROM reply_documents WHERE id=? AND state='draft'`, child, item.id);
  }
}

function approvesDraft(input: string) {
  const text = input.normalize('NFKC').trim().replace(/[，,。！!\s]+$/u, '');
  return /^(?:(?:好(?:的)?|可以)[，,。！!\s]*)?(?:按(?:这个|这条|这份草稿|草稿|原稿|原文)(?:直接|原样)?(?:发送|发|回复|回)(?:给对方)?|原样(?:发送|发|回复)|直接(?:发送|发)(?:吧)?|发吧|可以发了)$/u.test(text);
}

function retryAttempt(item: Attempt, guidance?: string) {
  if (item.reply === 'unknown') return item;
  if (item.reply === 'accepted') return '这条回复已经发送。';
  if (item.reply === 'pending' || item.reply === 'sending' || item.reply === 'waiting') return '这条任务正在处理，请等候结果。';
  if (item.reply !== 'failed' && item.state !== 'failed') return '这条任务没有可重试的失败记录。';
  if (item.reply === 'failed' && item.reply_text && (!guidance || wecomReplyParts(item.attempt).some((part) => part.state === 'accepted'))) {
    run("UPDATE runs SET reply='pending',updated=? WHERE id=? AND reply='failed'", now(), item.attempt);
    run('UPDATE wecom_commands SET feedback=0 WHERE id=?', item.attempt);
    const pendingFile = wecomReplyParts(item.attempt).some((part) => part.document_id && part.state !== 'accepted');
    return pendingFile ? '已排队继续发送未送达的附件。' : '已排队继续发送尚未受理的正文。';
  }
  const instruction = [item.instruction, '这是本人要求的重试，请以当前资料重新核对。', guidance?.trim().slice(0, 4000)]
    .filter(Boolean).join('\n');
  queueAnalysis(item, instruction, item.direct_text);
  return '已让 Pi 重新查证并决定如何回复。';
}

async function reconcileAttempt(bot: LiveBot, attempt: Attempt) {
  if (!attempt.reply_text) return '缺少原发送正文，无法核对发送结果。';
  try {
    if (wecomReplyParts(attempt.attempt).length) {
      if (!await reconcileWecomParts(bot, attempt.target, attempt.attempt)) return '原会话尚无法确认这条回复的发送结果。已保留待核对状态。';
      return '已核实该段送达。其余待发送内容将按顺序继续发送。';
    }
    const messages = await history(bot, attempt.target, attempt.updated - 999, now(), 4);
    const matches = messages.filter((item) => !item.system && item.sender === bot.settings.employeeId &&
      (attempt.receipt ? item.id === attempt.receipt : item.text === attempt.reply_text));
    if (matches.length === 1) {
      run(`UPDATE runs SET reply='accepted',receipt=?,sent_at=? WHERE id=? AND reply='unknown'
        AND EXISTS(SELECT 1 FROM bots WHERE id=? AND enabled=1 AND version=?)`,
        matches[0].id, matches[0].time, attempt.attempt, bot.id, bot.version);
      return '已在原会话核实这条回复送达。';
    }
  } catch {
    return '暂时无法读取原会话。发送结果仍待核对，可稍后再发「重试 编号」。';
  }
  return '原会话尚无法确认这条回复的发送结果。已保留待核对状态。';
}

export async function handleOwnerCommand(bot: LiveBot, message: HistoryMessage) {
  if (message.sender !== bot.settings.employeeId || message.system) return;
  const input = message.text.trim();
  // Replies sent to the owner's own conversation are also visible in its message history.
  if (!input || /^【(?:企微|飞书|钉钉)(?:接管[】待]|回复待确认】)/u.test(input)) return;
  const match = /^(接管|忽略|重试)\s+(最新|[0-9a-f-]{8,36})(?:\s+([\s\S]+))?$/i.exec(input);
  const list = input === '接管待办';
  const invalidCommand = !match && !list && /^(接管|忽略|重试)(?:\s|$)/u.test(input);
  const direct = !match && !list && !invalidCommand;

  const feedback = transaction(() => {
    if (!get('SELECT 1 FROM bots WHERE id=? AND enabled=1 AND version=?', bot.id, bot.version)) return;
    const values = [bot.id, bot.version, now() - reviewWindow] as const;
    const quoted = bot.platform === 'feishu' ? message.replyTo : undefined;
    const newest = pending + (bot.platform === 'wecom' ? ' AND r.created<=?' : ' AND v.notified<=?');
    const candidates = direct ? all<Candidate>(
      newest + (quoted ? ' AND v.notice_receipt=?' : '') + ' ORDER BY r.created DESC,r.rowid DESC LIMIT 2',
      ...values, message.time + (bot.platform === 'wecom' ? 999 : 0), ...(quoted ? [quoted] : [])) : [];
    if (direct && candidates.length === 0) return;
    const key = JSON.stringify([bot.settings.employeeId, message.id]);
    if (!run('INSERT OR IGNORE INTO inbox (bot,version,message,run) VALUES (?,?,?,?)', bot.id, bot.version, key, '').changes) return;
    if (invalidCommand) return '请发送「接管待办」，或输入「接管 编号」「忽略 编号」「重试 编号」。';
    if (list) {
      const rows = all<Candidate>(pending + ' ORDER BY r.created DESC,r.rowid DESC LIMIT 8', ...values);
      const attempts = all<Attempt>(latestAttempt + " AND (r.state='failed' OR r.reply IN ('failed','unknown')) ORDER BY r.updated DESC LIMIT 8", ...values);
      const items = new Map(attempts.map((item) => [item.id, item.reply === 'unknown' ? '待核对' : '可重试']));
      for (const item of rows) if (!items.has(item.id)) items.set(item.id, '待接管 ' + Array.from(item.prompt.replace(/\s+/g, ' ')).slice(0, 75).join(''));
      return items.size ? '接管待办\n' + [...items].map(([id, label]) => id.slice(0, 8) + ' ' + label).join('\n') +
        '\n\n发送「接管 编号」「忽略 编号」或「重试 编号」。' : '当前没有待处理的来信。';
    }
    const [, action, reference, instruction] = match ?? [];
    if (action === '重试') {
      const attempts = reference === '最新'
        ? all<Attempt>(latestAttempt + " AND (r.state='failed' OR r.reply IN ('failed','unknown')) ORDER BY r.updated DESC LIMIT 1", ...values)
        : all<Attempt>(latestAttempt + ' AND (substr(original.id,1,?)=? OR substr(r.id,1,?)=?) LIMIT 2',
          ...values, reference.length, reference.toLowerCase(), reference.length, reference.toLowerCase());
      return attempts.length === 1 ? retryAttempt(attempts[0], instruction) : '请发送「接管待办」核对可重试的编号。';
    }
    const rows = direct ? candidates : reference === '最新'
      ? all<Candidate>(pending + ' ORDER BY r.created DESC,r.rowid DESC LIMIT 1', ...values)
      : all<Candidate>(pending + ' AND substr(r.id,1,?)=? LIMIT 2', ...values, reference.length, reference.toLowerCase());
    if (rows.length !== 1) return '请发送「接管待办」核对来信，再发送「接管 编号」并附上你要处理的内容。';
    const item = rows[0];
    const label = item.id.slice(0, 8);
    if (action === '忽略') {
      run("UPDATE reply_reviews SET state='rejected',decided=? WHERE id=?", now(), item.id);
      run("UPDATE runs SET reply='canceled',updated=? WHERE id=?", now(), item.id);
      return '已忽略 ' + label + '，原会话未发送回复。';
    }
    const approved = bot.platform !== 'wecom' && approvesDraft(direct ? input : instruction ?? '') ? item.draft?.trim() : undefined;
    queueAnalysis(item, instruction?.trim().slice(0, 4000) ?? '', direct ? input : null, approved);
    if (approved) return '已收到确认，正在把 ' + label + ' 的草稿发回原会话。';
    return '已开始处理 ' + label + '，完成后会在这里告诉你结果。';
  });
  if (feedback) await selfFeedback(bot, typeof feedback === 'string' ? feedback : await reconcileAttempt(bot, feedback), message.id);
}

async function selfFeedback(bot: LiveBot, text: string, id: string) {
  const target = reviewerTarget(bot);
  if (!target) return;
  const platform = bot.platform === 'wecom' ? '企微' : bot.platform === 'dingtalk' ? '钉钉' : '飞书';
  try { await sendPersonal(bot, target.id, '【' + platform + '接管】\n' + text, bot.platform === 'wecom' ? id : 'ack-' + id); }
  catch { console.warn(platform + '接管指令反馈发送未获确认'); }
}

export async function notifyCommandResults(bot: LiveBot) {
  const completed = all<{ id: string; original: string; reply: string; decision: string | null; text: string | null; reply_text: string | null; original_reply: string; error: string | null }>(`
    SELECT w.id,w.original,w.decision,w.text,r.reply,r.reply_text,r.error,original.reply AS original_reply
    FROM wecom_commands w JOIN runs r ON r.id=w.id JOIN runs original ON original.id=w.original
    JOIN chats c ON c.id=r.chat WHERE c.bot=? AND c.version=? AND w.feedback=0
    AND r.reply IN ('accepted','canceled','failed','unknown','skipped') ORDER BY r.updated LIMIT 10`, bot.id, bot.version);
  for (const item of completed) {
    if (!run(`UPDATE wecom_commands SET feedback=1 WHERE id=? AND feedback=0
      AND EXISTS(SELECT 1 FROM bots WHERE id=? AND enabled=1 AND version=?)`, item.id, bot.id, bot.version).changes) continue;
    const result = item.reply === 'accepted' ? '已回复原会话\n' + Array.from(item.reply_text ?? '').slice(0, 1500).join('') :
      item.reply === 'unknown' ? '回复发送结果待核对，可发「重试 ' + item.original.slice(0, 8) + '」核对原会话。' :
      item.reply === 'failed' ? (bot.platform === 'wecom' ? '企微' : bot.platform === 'dingtalk' ? '钉钉' : '飞书') + '未受理发送。' + (item.error ? '\n' + item.error + '。' : '') +
        '\n可发「重试 ' + item.original.slice(0, 8) + '」继续发送。' :
      item.decision === 'skip' ? 'Pi 判断无需回复。' + (item.text ?? '') :
      item.decision === 'keep' ? '原来信仍在待办里。' + (item.text ?? '') :
      item.original_reply === 'awaiting_review' ? '处理未完成，原来信已恢复到待办。可发「重试 ' + item.original.slice(0, 8) + '」。' :
        '本次未完成回复，请查看原会话或重新发起测试。';
    await selfFeedback(bot, item.original.slice(0, 8) + ' ' + result, item.id);
  }
}
