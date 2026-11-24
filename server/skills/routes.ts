import express from 'express';
import multer from 'multer';
import { bytesPerMiB } from '../../shared/config.ts';
import type { BotSettings } from '../../shared/types.ts';
import { requireUser } from '../account/auth.ts';
import { httpError, parse } from '../common/runtime.ts';
import { all, owned, run } from '../storage/store.ts';
import { uploadSkill } from './skills.ts';

import { skillConfig } from './skills.config.ts';

export function installSkillRoutes(app: express.Express) {
  const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: skillConfig.uploadMaxMb * bytesPerMiB, files: 1, fields: 0 } });
  app.get('/api/skills', (req, res) => res.json(all('SELECT id,name,description,created FROM skills WHERE owner=? AND archived=0 ORDER BY created DESC', requireUser(req).id)));
  app.post('/api/skills', upload.single('file'), (req, res) => res.status(201).json(uploadSkill(requireUser(req).id, req.file)));
  app.delete('/api/skills/:id', (req, res) => {
    const skill = owned('skills', requireUser(req).id, req.params.id);
    const inUse = all<{ skills: string }>('SELECT skills FROM schedules WHERE owner=? AND enabled=1', requireUser(req).id).some((item) => parse<string[]>(item.skills).includes(skill.id)) ||
      all<{ settings: string }>('SELECT settings FROM bots WHERE owner=? AND enabled=1', requireUser(req).id).some((item) => parse<BotSettings>(item.settings).skills.includes(skill.id));
    if (inUse) throw httpError(409, '请先在已启用的定时任务或机器人连接中移除此 Skill');
    run('UPDATE skills SET archived=1 WHERE id=?', skill.id);
    res.sendStatus(204);
  });
}
