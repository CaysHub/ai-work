import { randomUUID } from 'node:crypto';
import { CronExpressionParser } from 'cron-parser';
import type { Express } from 'express';
import type { SaveRequest, Response, StoredTables } from './types.ts';
import { requireUser } from './auth.ts';
import { z } from 'zod';
import { now, parse, httpError } from './config.ts';
import { all, createChat, enqueue, owned, run, transaction } from './store.ts';
import { skillRows } from './files.ts';

export const skillsField = z.array(z.uuid()).max(8).default([]).transform((ids) => [...new Set(ids)]);
export const promptField = z.string().trim().min(1).max(24000);
const fields = z.object({
  name: z.string().trim().min(1).max(120), prompt: promptField, skills: skillsField,
  cron: z.string().trim().min(1).max(100), tz: z.string().trim().min(1).max(80).default('Asia/Shanghai'),
  enabled: z.boolean().default(true),
});
function nextDate(cron: string, tz: string) {
  try {
    if (cron.split(/\s+/).length !== 5) throw new Error();
    new Intl.DateTimeFormat('zh-CN', { timeZone: tz });
    return CronExpressionParser.parse(cron, { tz, currentDate: new Date(now()) }).next().getTime();
  } catch {
    throw httpError(400, '请输入有效的五段 Cron 表达式和时区');
  }
}
function trigger(task: StoredTables['schedules']) {
  return enqueue(createChat(task.owner, task.name, 'schedule'), task.prompt, parse(task.skills), { task: task.id });
}
export function tickSchedules() {
  transaction(() => {
    for (const task of all<StoredTables['schedules']>('SELECT * FROM schedules WHERE enabled=1 AND next<=? ORDER BY next LIMIT 30', now())) {
      run('UPDATE schedules SET next=? WHERE id=?', nextDate(task.cron, task.tz), task.id);
      trigger(task);
    }
  });
}
export function installSchedules(app: Express) {
  app.get('/api/schedules', (req, res) => res.json(all<StoredTables['schedules']>(`SELECT s.*,
    (SELECT state FROM runs WHERE task=s.id ORDER BY created DESC LIMIT 1) AS last_state,
    (SELECT chat FROM runs WHERE task=s.id ORDER BY created DESC LIMIT 1) AS last_chat
    FROM schedules s WHERE owner=? ORDER BY created DESC`, requireUser(req).id).map((row) => ({ ...row, skills: parse(row.skills) }))));
  function save(req: SaveRequest, res: Response) {
    const owner = requireUser(req).id;
    const id = req.params.id ? owned('schedules', owner, req.params.id).id : randomUUID();
    const data = fields.parse(req.body);
    skillRows(owner, data.skills);
    const next = nextDate(data.cron, data.tz);
    run(`INSERT INTO schedules VALUES (?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,prompt=excluded.prompt,skills=excluded.skills,
      cron=excluded.cron,tz=excluded.tz,enabled=excluded.enabled,next=excluded.next`,
    id, owner, data.name, data.prompt, JSON.stringify(data.skills), data.cron, data.tz, Number(data.enabled), data.enabled ? next : null, now());
    res.json({ id });
  }
  app.post('/api/schedules', save);
  app.put('/api/schedules/:id', save);
  app.delete('/api/schedules/:id', (req, res) => {
    run('DELETE FROM schedules WHERE owner=? AND id=?', requireUser(req).id, req.params.id);
    res.sendStatus(204);
  });
  app.post('/api/schedules/:id/run', (req, res) => {
    const task = owned('schedules', requireUser(req).id, req.params.id);
    const id = transaction(() => trigger(task));
    res.status(202).json({ id });
  });
}
