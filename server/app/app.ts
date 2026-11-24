import type { ErrorRequestHandler } from 'express';
import express from 'express';
import multer from 'multer';
import { resolve } from 'node:path';
import { z } from 'zod';
import { installAuth } from '../account/auth.ts';
import { modelReady } from '../agent/worker.ts';
import { installAuthorizations, installFeishuCallback } from '../bots/authorization.ts';
import { installBots } from '../bots/routes.ts';
import { httpError } from '../common/runtime.ts';
import { config, rootDir } from '../config/env.ts';
import { installDocumentLibrary } from '../documents/library.ts';
import { installReplies } from '../replies/reviews.ts';
import { installSchedules } from '../schedules/schedules.ts';

import { bytesPerMiB, httpPaths } from '../../shared/config.ts';
import { installChatRoutes } from '../chat/routes.ts';
import { installSkillRoutes } from '../skills/routes.ts';
import { skillConfig } from '../skills/skills.config.ts';
import { appConfig } from './app.config.ts';

export function createApp(cancelRun: (chat: string, id: string) => Promise<void>) {
  const app = express();
  app.disable('x-powered-by');
  app.use((_req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'no-referrer');
    next();
  });
  app.use(httpPaths.api, (_req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
  app.use(express.json({ limit: appConfig.jsonBodyLimit }));
  app.get('/api/health', (_req, res) => res.json({ ok: true }));
  installFeishuCallback(app);
  installAuth(app);
  app.get('/api/settings', (_req, res) => res.json({ model: config.model.id, ready: modelReady(), documentMaxBytes: config.documentMaxBytes }));
  installSkillRoutes(app);
  installChatRoutes(app, cancelRun);
  installSchedules(app);
  installBots(app);
  installReplies(app, cancelRun);
  installDocumentLibrary(app);
  installAuthorizations(app);
  app.use(httpPaths.api, () => { throw httpError(404, '接口不存在'); });
  app.use(express.static(resolve(rootDir, appConfig.staticDirectory)));
  app.get('/', (_req, res) => res.sendFile(resolve(rootDir, appConfig.staticDirectory, appConfig.entryPage)));
  const handleError: ErrorRequestHandler = (error: unknown, req, res, next) => {
    if (res.headersSent) return next(error);
    if (error instanceof z.ZodError) return res.status(400).json({ error: '请检查输入字段：' + error.issues.map((item) => item.path.join('.')).join('、') });
    if (error instanceof multer.MulterError) {
      const limit = req.path.startsWith('/api/documents') ? config.documentMaxBytes / bytesPerMiB : skillConfig.uploadMaxMb;
      return res.status(error.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ error: '每次请求允许上传一个不超过 ' + limit + ' MB 的文件' });
    }
    const status = error instanceof Error && 'status' in error && typeof error.status === 'number' ? error.status : 500;
    if (status >= 500) console.error(error);
    res.status(status).json({ error: status < 500 && error instanceof Error ? error.message : '服务内部错误' });
  };
  app.use(handleError);
  return app;
}
