import { config } from './config.ts';
import { errorMessage } from '../shared/types.ts';
import { attachDocuments, saveWecomAttachment } from './document-library.ts';
import { platformSignal, wecomCall } from './platform.ts';
import type { HistoryMessage } from './platform.ts';
import { all, get, run, transaction, unseal } from './store.ts';
import type { AgentRun, LiveBot, Secrets } from './types.ts';

const fileLimit = Math.min(config.documentMaxBytes, 5 * 1024 * 1024);
const pairingWindow = 10 * 60 * 1000;
type WecomMedia = {
  base64_content?: string;
  file_name?: string;
  media_item?: { media_id?: string; file_name?: string; file_path?: { content?: string; file_name?: string } };
};

export function rememberWecomFile(bot: LiveBot, target: string, message: HistoryMessage) {
  if (!message.file || !message.sender || message.file.mediaId.length > 256) return;
  run(`INSERT OR IGNORE INTO wecom_inbound_files (bot,version,target,sender,message,media,name,received)
    VALUES (?,?,?,?,?,?,?,?)`, bot.id, bot.version, target, message.sender, message.id, message.file.mediaId,
  message.file.name ?? null, message.time);
}

export function pairWecomFiles(bot: LiveBot, target: string, sender: string, time: number, id: string) {
  run(`UPDATE wecom_inbound_files SET run_id=? WHERE rowid IN (
    SELECT rowid FROM wecom_inbound_files WHERE bot=? AND version=? AND target=? AND sender=?
    AND run_id IS NULL AND received BETWEEN ? AND ? ORDER BY received DESC,rowid DESC LIMIT 10
  )`, id, bot.id, bot.version, target, sender, time - pairingWindow, time);
}

export async function fetchRunWecomFiles(row: AgentRun, original?: string) {
  if (!row.bot || row.source !== 'wecom') return [];
  const id = original ?? row.id;
  const files = all<{ message: string; media: string; name: string | null }>(`SELECT message,media,name FROM wecom_inbound_files
    WHERE run_id=? AND bot=? AND version=? AND target=? ORDER BY received,rowid`, id, row.bot, row.version, row.target);
  if (!files.length) return [];
  const match = get<{ secrets: string }>("SELECT secrets FROM bots WHERE id=? AND owner=? AND enabled=1 AND version=?", row.bot, row.owner, row.version);
  if (!match) return ['企微文件授权已变更'];
  const { messageMcp } = unseal<Secrets>(match.secrets);
  const problems: string[] = [];
  const signal = platformSignal.getStore();
  for (const file of files) {
    signal?.throwIfAborted();
    try {
      const existing = get<{ id: string }>('SELECT id FROM documents WHERE owner=? AND source_key=?',
        row.owner, 'wecom:' + row.chat + ':' + file.message);
      const response = existing ? undefined : await wecomCall<WecomMedia>(
        messageMcp, 'msg', 'message_files_get', { media_id: file.media });
      signal?.throwIfAborted();
      const item = response?.media_item;
      if (item?.media_id && item.media_id !== file.media) throw new Error('企微返回了其他附件');
      const name = item?.file_path?.file_name || item?.file_name || response?.file_name || file.name || '企微附件';
      let id = existing?.id;
      if (!id) {
        const encoded = item?.file_path?.content ?? response?.base64_content;
        if (!encoded || typeof encoded !== 'string') throw new Error('企微响应缺少文件内容');
        if (encoded.length > Math.ceil(fileLimit / 3) * 4) throw new Error('文件超过大小限制');
        if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded) || encoded.replace(/=+$/, '').length % 4 === 1) {
          throw new Error('企微返回的文件编码无效');
        }
        const bytes = Buffer.from(encoded, 'base64');
        if (bytes.length > fileLimit) throw new Error('文件超过大小限制');
        id = saveWecomAttachment(row, name, bytes, file.message);
      }
      const documentId = id;
      transaction(() => {
        if (!get('SELECT 1 FROM document_inputs WHERE run_id=? AND document_id=?', row.id, documentId)) {
          attachDocuments(row.owner, row.chat, row.id, [documentId]);
        }
      });
    } catch (error) {
      signal?.throwIfAborted();
      const reason = errorMessage(error).replace(/https?:\/\/\S+/g, '[地址]').slice(0, 200);
      problems.push((file.name ?? '企微附件') + ' 读取失败，' + reason);
    }
  }
  return problems;
}
