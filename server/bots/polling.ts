import type { BotSettings } from '../../shared/types.ts';
import { now } from '../common/runtime.ts';
import type { LiveBot, ReviewSender } from '../common/types.ts';
import { history, wecomGroups } from '../platform/messages.ts';
import { rememberWecomFile } from '../replies/wecom-files.ts';
import { all, get, run, transaction } from '../storage/store.ts';
import { freshToken, verifyEmployee } from './authorization.ts';
import { botConfig } from './bots.config.ts';

import { accept, groupMentionPattern } from './inbound.ts';
import { current, enabledBots, reviewRoute, setStatus } from './records.ts';

  export async function pollPersonal(bot: LiveBot, isClosed: () => boolean,
  reviewSender: (personal: LiveBot, route: NonNullable<ReturnType<typeof reviewRoute>>) => ReviewSender | undefined) {
  bot.secrets = await freshToken(bot);
  await verifyEmployee(bot.platform, bot.secrets, bot.settings.employeeId);
  const { employeeId, groupMentionName } = bot.settings;
  const mentionPattern = groupMentionName ? groupMentionPattern(groupMentionName) : undefined;
  type Target = BotSettings['targets'][number] & { name?: string | null; first_from?: number };
  const targets = new Map<string, Target>(bot.settings.targets.map((target) => [target.id, target]));
  let failures = 0;
  const end = now() - botConfig.historyLagMs;
  if (bot.platform === 'wecom' && bot.settings.discoverGroups !== false) {
    const checkpoint = get<{ checkpoint: number }>('SELECT checkpoint FROM group_checkpoints WHERE bot=? AND version=?', bot.id, bot.version)?.checkpoint;
    if (end > bot.since && (!checkpoint || end - checkpoint >= botConfig.discoveryIntervalMs)) {
      const start = Math.max(bot.since, end - botConfig.historyBacklogMs, (checkpoint ?? end - botConfig.discoveredGroupLifetimeMs) - botConfig.historyOverlapMs);
      try {
        const groups = await wecomGroups(bot, start, end);
        if (isClosed() || !current(bot)) return;
        transaction(() => {
          for (const group of groups) run(`INSERT INTO discovered_groups VALUES (?,?,?,?,?,?)
            ON CONFLICT(bot,version,target) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen`,
          bot.id, bot.version, group.id, group.name ?? null, start, end);
          run(`INSERT INTO group_checkpoints VALUES (?,?,?) ON CONFLICT(bot,version)
            DO UPDATE SET checkpoint=excluded.checkpoint`, bot.id, bot.version, end);
        });
      } catch { failures++; }
    }
    for (const group of all<Target>(`SELECT target AS id,name,first_from FROM discovered_groups
      WHERE bot=? AND version=? AND last_seen>=?`, bot.id, bot.version, end - botConfig.discoveredGroupLifetimeMs)) {
      targets.set(group.id, { ...group, group: true });
    }
  }
  async function pollTarget(target: Target) {
    if (isClosed() || !current(bot)) return;
    const checkpoint = get<{ checkpoint: number }>('SELECT checkpoint FROM cursors WHERE bot=? AND version=? AND target=?', bot.id, bot.version, target.id)?.checkpoint;
    const floor = target.first_from ?? bot.since;
    const start = Math.max(floor, (checkpoint ?? floor) - botConfig.historyOverlapMs);
    if (start >= end) return;
    if (end - start > botConfig.historyBacklogMs) throw new Error('接管积压超过六天，请重新启用连接');
    const items = await history(bot, target.id, start, end);
    if (isClosed() || !current(bot)) return;
    const textMention = mentionPattern && target.group && bot.settings.targets.some(({ id, group }) => group && id === target.id)
      ? mentionPattern : undefined;
    if (bot.platform === 'wecom') {
      for (const item of items) {
        if (item.file && !item.system && item.sender !== employeeId) rememberWecomFile(bot, target.id, item);
      }
    }
    for (const item of items) {
      if (item.system || item.sender === employeeId) continue;
      if (target.group) {
        const addressed = item.mentions.length ? item.mentions.includes(employeeId) : item.textMessage && textMention?.test(item.text);
        if (!addressed) continue;
      }
      if (items.some((later) => !later.system && later.sender === employeeId && later.time >= item.time)) continue;
      accept(bot, target.id, item, { channel: 'personal', sourceTime: item.time, sourceId: item.id }, target.name ?? target.id);
    }
    run(`INSERT INTO cursors VALUES (?,?,?,?) ON CONFLICT(bot,version,target) DO UPDATE SET checkpoint=excluded.checkpoint`,
      bot.id, bot.version, target.id, end);
  }
  const list = [...targets.values()];
  for (let index = 0; index < list.length && !isClosed() && current(bot); index += botConfig.pollingConcurrency) {
    const results = await Promise.allSettled(list.slice(index, index + botConfig.pollingConcurrency).map(pollTarget));
    failures += results.filter((result) => result.status === 'rejected').length;
  }
  const active = enabledBots();
  const personal = active.find((item) => item.id === bot.id && item.version === bot.version);
  const route = personal && reviewRoute(personal, active);
  setStatus(bot, failures ? 'error' : 'online', failures ? failures + ' 项会话读取或群聊发现失败' :
    !personal || !route ? '请绑定接管机器人以接收待办' : !reviewSender(personal, route) ? '等待接管机器人上线' : null);
}
