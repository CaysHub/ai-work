import { now } from './config.ts';
import { history, sendPersonal } from './platform.ts';
import { all, get, run, transaction } from './store.ts';
import type { LiveBot } from './types.ts';

type ReplyPart = { part: number; text: string; state: string; receipt: string | null; attempted: number | null;
  document_id: string | null; media_id: string | null };

export function wecomReplyParts(id: string) {
  return all<ReplyPart>('SELECT part,text,state,receipt,attempted,document_id,media_id FROM wecom_reply_parts WHERE run_id=? ORDER BY part', id);
}

export function beginWecomDelivery(id: string, proposed: string[]) {
  return transaction(() => {
    const previous = wecomReplyParts(id);
    const text = (previous.length ? previous.map((part) => part.text) : proposed).join('\n\n');
    if (!text || !run("UPDATE runs SET reply='sending',reply_text=?,updated=? WHERE id=? AND reply='pending'",
      text, now(), id).changes) return false;
    if (!previous.length) {
      for (const [index, part] of proposed.entries()) {
        run('INSERT INTO wecom_reply_parts (run_id,part,text) VALUES (?,?,?)', id, index + 1, part);
      }
    }
    return true;
  });
}

export async function sendWecomParts(bot: LiveBot, target: string, id: string, canSend: () => boolean) {
  let receipt: string | null = null;
  for (const part of wecomReplyParts(id)) {
    if (part.state === 'accepted') {
      receipt = part.receipt;
      continue;
    }
    if (!canSend()) throw Object.assign(new Error('企微连接已停用'), { canceled: true });
    if (part.document_id) {
      run("UPDATE wecom_reply_parts SET state='failed' WHERE run_id=? AND part=? AND state='pending'", id, part.part);
      throw Object.assign(new Error('企微本人身份只支持文字，附件请通过在线文档链接交付'), { rejected: true });
    }
    if (!run(`UPDATE wecom_reply_parts SET state='sending',attempted=? WHERE run_id=? AND part=? AND state IN ('pending','failed')`,
      now(), id, part.part).changes) throw new Error('企微分段发送结果待核对');
    try {
      receipt = await sendPersonal(bot, target, part.text, id);
      run("UPDATE wecom_reply_parts SET state='accepted',receipt=?,accepted=? WHERE run_id=? AND part=?", receipt, now(), id, part.part);
    } catch (error) {
      const rejected = error instanceof Error && 'rejected' in error && error.rejected;
      run('UPDATE wecom_reply_parts SET state=? WHERE run_id=? AND part=?', rejected ? 'failed' : 'unknown', id, part.part);
      throw error;
    }
  }
  return receipt;
}

export function recoverWecomDeliveries() {
  transaction(() => {
    run("UPDATE wecom_reply_parts SET state='unknown' WHERE state='sending'");
    run(`UPDATE runs SET reply='pending' WHERE reply='sending'
      AND EXISTS(SELECT 1 FROM wecom_reply_parts WHERE run_id=runs.id)
      AND NOT EXISTS(SELECT 1 FROM wecom_reply_parts WHERE run_id=runs.id AND state='unknown')`);
    run("UPDATE runs SET reply='unknown' WHERE reply='sending'");
  });
}

export async function reconcileWecomParts(bot: LiveBot, target: string, id: string) {
  const part = wecomReplyParts(id).find((item) => item.state === 'unknown');
  if (!part?.attempted) return false;
  const messages = await history(bot, target, part.attempted - 999, now(), 4);
  const matches = messages.filter((item) => !item.system && (part.document_id
    ? Boolean(part.media_id && item.file?.mediaId === part.media_id)
    : item.sender === bot.settings.employeeId && item.text === part.text));
  if (matches.length !== 1) return false;
  return transaction(() => {
    if (!get("SELECT 1 FROM bots WHERE id=? AND version=? AND enabled=1", bot.id, bot.version)) return false;
    run("UPDATE wecom_reply_parts SET state='accepted',receipt=?,accepted=? WHERE run_id=? AND part=? AND state='unknown'",
      matches[0].id, matches[0].time, id, part.part);
    const remaining = get("SELECT 1 FROM wecom_reply_parts WHERE run_id=? AND state<>'accepted'", id);
    run("UPDATE runs SET reply=?,receipt=?,sent_at=?,updated=? WHERE id=? AND reply='unknown'",
      remaining ? 'pending' : 'accepted', matches[0].id, remaining ? null : matches[0].time, now(), id);
    if (remaining) run('UPDATE wecom_commands SET feedback=0 WHERE id=?', id);
    return true;
  });
}
