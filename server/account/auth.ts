import type { Express, Request, Response } from 'express';
import { createHash, randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import { accountLimits } from '../../shared/config.ts';
import type { User, UserRole } from '../../shared/types.ts';
import { httpError, now } from '../common/runtime.ts';
import { config } from '../config/env.ts';
import { all, get, run, transaction } from '../storage/store.ts';
import { authConfig } from './auth.config.ts';

const account = z.object({
  username: z.string().trim().toLowerCase().regex(accountLimits.usernamePattern),
  name: z.string().trim().min(1).max(accountLimits.displayName),
  password: z.string().min(accountLimits.passwordMin).max(accountLimits.passwordMax),
});
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
const cookies = (req: Request): Record<string, string | undefined> => Object.fromEntries((req.headers.cookie ?? '').split(';').map((part) => part.trim().split(/=(.*)/s, 2)));

function createUser(input: unknown, role: UserRole): User {
  const { username, name, password } = account.parse(input);
  if (get('SELECT 1 FROM users WHERE username=?', username)) throw httpError(409, '用户名已存在');
  const salt = randomBytes(authConfig.saltBytes).toString('hex');
  const id = randomUUID();
  run('INSERT INTO users VALUES (?,?,?,?,?,?)', id, username, name, salt + ':' + scryptSync(password, salt, authConfig.passwordHashBytes).toString('hex'), role, now());
  return { id, username, name, role };
}
function setSession(res: Response, user: User) {
  const token = randomBytes(authConfig.sessionTokenBytes).toString('hex');
  run('DELETE FROM auth WHERE expires<=?', now());
  run('INSERT INTO auth VALUES (?,?,?)', digest(token), user.id, now() + authConfig.sessionLifetimeMs);
  res.cookie(authConfig.cookie, token, { httpOnly: true, sameSite: 'strict', secure: Boolean(config.secureCookies), maxAge: authConfig.sessionLifetimeMs, path: '/' });
  res.json(user);
}
const attempts = new Map<string, { count: number; until: number }>();
function limit(req: Request) {
  const time = now();
  for (const [ip, attempt] of attempts) if (attempt.until < time) attempts.delete(ip);
  const address = req.ip ?? req.socket.remoteAddress ?? '';
  const attempt = attempts.get(address) ?? { count: 0, until: time + authConfig.loginWindowMs };
  if (++attempt.count > authConfig.loginMaxAttempts) throw httpError(429, '请求过于频繁，请稍后重试');
  attempts.set(address, attempt);
}

export function requireUser(req: Request): User {
  if (!req.user) throw httpError(401, '请先登录');
  return req.user;
}

export function installAuth(app: Express) {
  app.use('/api', (req, _res, next) => {
    if (req.headers['sec-fetch-site'] === 'cross-site') throw httpError(403, '禁止跨站访问');
    const token = cookies(req)[authConfig.cookie];
    if (token) req.user = get<User>(`SELECT u.id,u.username,u.name,u.role FROM auth a
      JOIN users u ON u.id=a.owner WHERE a.token=? AND a.expires>?`, digest(token), now());
    next();
  });
  app.get('/api/auth/status', (req, res) => res.json({ user: req.user ?? null, setup: !get('SELECT 1 FROM users LIMIT 1') }));
  app.post('/api/auth/setup', (req, res) => {
    limit(req);
    if (!authConfig.setupAddresses.includes(req.socket.remoteAddress ?? '')) {
      throw httpError(403, '请在服务器本机初始化管理员');
    }
    const user = transaction(() => {
      if (get('SELECT 1 FROM users LIMIT 1')) throw httpError(409, '系统已初始化');
      return createUser(req.body, 'admin');
    });
    setSession(res, user);
  });
  app.post('/api/auth/login', (req, res) => {
    limit(req);
    const { username, password } = account.pick({ username: true, password: true }).parse(req.body);
    const row = get<User & { password: string }>('SELECT * FROM users WHERE username=?', username);
    const [salt, expected] = row?.password.split(':') ?? ['invalid', '0'.repeat(authConfig.passwordHashBytes * 2)];
    const valid = timingSafeEqual(scryptSync(password, salt, authConfig.passwordHashBytes), Buffer.from(expected, 'hex'));
    if (!row || !valid) throw httpError(401, '用户名或密码错误');
    setSession(res, { id: row.id, username: row.username, name: row.name, role: row.role });
  });
  app.use('/api', (req, _res, next) => {
    if (!req.user) throw httpError(401, '请先登录');
    next();
  });
  app.post('/api/auth/logout', (req, res) => {
    run('DELETE FROM auth WHERE token=?', digest(cookies(req)[authConfig.cookie] ?? ''));
    res.clearCookie(authConfig.cookie, { path: '/' }).sendStatus(204);
  });
  app.use('/api/users', (req, _res, next) => {
    if (requireUser(req).role !== 'admin') throw httpError(403, '需要管理员权限');
    next();
  });
  app.get('/api/users', (_req, res) => res.json(all('SELECT id,username,name,role,created FROM users ORDER BY created')));
  app.post('/api/users', (req, res) => res.status(201).json(createUser(req.body, 'member')));
}
