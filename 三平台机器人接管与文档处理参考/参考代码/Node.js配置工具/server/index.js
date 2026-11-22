import { randomBytes, timingSafeEqual } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import express from 'express';
import { createServer as createViteServer } from 'vite';
import { Backend, ConfigError } from './backend.js';
import { capabilities, presets, preloaded } from './presets.js';
import { inspect, apply, action } from './configuration.js';

const project = fileURLToPath(new URL('../', import.meta.url));
const portArg = process.argv.indexOf('--port');
const port = Number(portArg >= 0 ? process.argv[portArg + 1] : process.env.IM_BOT_CONFIG_PORT || '18767');
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error('端口须为 1 至 65535 的整数');
  process.exit(1);
}

const host = '127.0.0.1';
const address = 'http://' + host + ':' + port;
const localToken = randomBytes(32).toString('hex');
const app = express();
app.disable('x-powered-by');
app.use((req, res, next) => {
  if (req.headers.host !== host + ':' + port) return res.status(403).json({ success: false, message: '请使用本机服务地址' });
  res.set({ 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer', 'X-Frame-Options': 'DENY' });
  next();
});

app.get('/api/bootstrap', (_req, res) => {
  res.json({ success: true, data: { token: localToken, presets, capabilities } });
});

app.use('/api', (req, res, next) => {
  const provided = Buffer.from(req.get('x-local-token') || '');
  const token = Buffer.from(localToken);
  if ((req.get('origin') && req.get('origin') !== address) || req.get('sec-fetch-site') === 'cross-site' ||
      provided.length !== token.length || !timingSafeEqual(provided, token)) {
    return res.status(403).json({ success: false, message: '请从本机配置页发起请求' });
  }
  next();
}, express.json({ limit: '100kb' }));

function scrub(message, input) {
  const secrets = [input?.cookie, input?.options?.secret, input?.code, input?.state,
    ...Object.values(input?.options?.mcpUrls ?? {}),
    input?.application?.secret, ...Object.values(input?.application?.mcpUrls ?? {}),
    ...Object.values(preloaded).flatMap(value => [value?.secret, ...Object.values(value?.mcpUrls ?? {})])];
  const fragments = secrets.flatMap(value => {
    if (typeof value !== 'string' || !value) return [];
    const matches = [...value.matchAll(/(?:^|[;?&\s])[^=;?&\s]+\s*=\s*([^;\s&]+)/g)];
    return [value, ...matches.map(match => match[1])];
  }).filter(Boolean).sort((a, b) => b.length - a.length);
  for (const part of new Set(fragments)) message = message.replaceAll(part, '****');
  return message;
}

for (const [name, handler] of Object.entries({ inspect, apply, action })) {
  app.post('/api/' + name, async (req, res) => {
    const steps = [];
    const input = { ...req.body, cookie: req.headers.cookie };
    try {
      if (!req.body || Array.isArray(req.body) || typeof req.body !== 'object') throw new ConfigError('请求格式不正确');
      const client = new Backend(input, steps);
      const data = await handler(input, client);
      res.json({ success: true, data: { ...data, steps } });
    } catch (error) {
      const known = error instanceof ConfigError;
      res.status(known ? 400 : 500).json({
        success: false, code: known ? error.code : 'LOCAL_ERROR',
        message: known ? scrub(error.message, input) : '操作未完成，请刷新状态核对',
        steps,
      });
    }
  });
}
app.use('/api', (_req, res) => res.status(404).json({ success: false, message: '未找到接口' }));
app.use((error, _req, res, next) => {
  if (res.headersSent) return next(error);
  res.status(error.status === 413 ? 413 : 400).json({ success: false, message: '请求格式或大小不正确' });
});

const vite = await createViteServer({
  root: project,
  configFile: path.join(project, 'vite.config.js'),
  server: {
    middlewareMode: true,
    hmr: false,
    fs: { deny: ['**/.env*', '**/.git/**', '**/server/**', '**/test/**', '**/node_modules/.cache/**'] },
  },
  appType: 'spa',
});
app.use(vite.middlewares);

const server = app.listen(port, host, () => console.log('机器人配置页已启动 ' + address));
server.on('error', async error => {
  console.error(error.code === 'EADDRINUSE' ? '端口已占用，请使用 --port 指定其他端口' : '本机服务启动失败');
  await vite.close();
  process.exitCode = 1;
});
let stopping = false;
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => {
  if (stopping) return;
  stopping = true;
  server.close();
  server.closeAllConnections();
  await vite.close();
});
