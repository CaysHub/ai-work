import type { Bot, BotSettings } from '../../shared/types.ts';
import { botCredentialKeys } from '../../shared/types.ts';
import { parse } from '../common/runtime.ts';
import type { LiveBot, Secrets, StoredTables } from '../common/types.ts';
import { all, get, run, unseal } from '../storage/store.ts';

export const hydrate = (bot: StoredTables['bots']): LiveBot => ({ ...bot, settings: parse<BotSettings>(bot.settings), secrets: unseal<Secrets>(bot.secrets) });
export function show(bot: StoredTables['bots']): Bot {
  const { secrets, ...visible } = hydrate(bot);
  return { ...visible, configured: botCredentialKeys.filter((key) => secrets[key]) };
}
export const current = (bot: LiveBot) => get('SELECT 1 FROM bots WHERE id=? AND version=? AND enabled=1', bot.id, bot.version);
export const enabledBots = () => all<StoredTables['bots']>('SELECT * FROM bots WHERE enabled=1').map(hydrate);
export function reviewRoute(personal: LiveBot, bots: LiveBot[]) {
  const { reviewBotId, reviewUserId, employeeId } = personal.settings;
  const bot = bots.find((item) => item.mode === 'bot' && item.platform === personal.platform && item.owner === personal.owner &&
    (reviewBotId ? item.id === reviewBotId : personal.platform === 'feishu' && personal.secrets.appId && item.secrets.appId === personal.secrets.appId));
  if (!bot) return;
  const sameFeishuApp = personal.platform === 'feishu' && Boolean(personal.secrets.appId) && personal.secrets.appId === bot.secrets.appId;
  const recipient = sameFeishuApp ? employeeId : reviewUserId ?? (personal.platform === 'wecom' ? employeeId : undefined);
  if (!recipient) return;
  return { bot, recipient, key: JSON.stringify([bot.id, bot.version, recipient]) };
}
export function setStatus(bot: LiveBot, status: Bot['status'], detail: string | null = null) {
  run('UPDATE bots SET status=?,detail=? WHERE id=? AND version=?', status, detail, bot.id, bot.version);
}
