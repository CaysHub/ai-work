import { randomUUID } from 'node:crypto';
import { closeSync, constants, lstatSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import { httpError } from '../common/runtime.ts';
import { config } from '../config/env.ts';
import { fileConfig, maxTextFileBytes } from './files.config.ts';

export function pathParts(relative: unknown) {
  if (typeof relative !== 'string' || !relative || relative.length > fileConfig.pathLength) throw httpError(400, '文件路径无效');
  const segments = relative.replaceAll('\\', '/').split('/');
  if (segments.some((part) => !part || part === '.' || part === '..' || /[<>:"|?*\u0000-\u001f]/.test(part) ||
    /[ .]$/.test(part) || /^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(?:\.|$)/i.test(part))) {
    throw httpError(400, '文件路径无效');
  }
  return segments;
}
export function checkPath(root: string, relative: string, createParents = false) {
  const segments = pathParts(relative);
  const target = resolve(root, ...segments);
  if (!target.startsWith(resolve(root) + sep)) throw httpError(400, '文件路径越界');
  let current = root;
  for (let index = 0; index < segments.length; index++) {
    current = join(current, segments[index]);
    const last = index === segments.length - 1;
    const info = lstatSync(current, { throwIfNoEntry: false });
    if (info?.isSymbolicLink() || (!last && info && !info.isDirectory())) throw httpError(400, '文件路径不可用');
    if (!info && !last && createParents) mkdirSync(current);
  }
  return target;
}
export const workspace = (chat: string) => join(config.dataDir, fileConfig.directory, chat);
export function readText(root: string, path: string) {
  const file = checkPath(root, path);
  const info = lstatSync(file);
  if (!info.isFile() || info.size > maxTextFileBytes) throw httpError(400, '只支持 ' + fileConfig.textMaxMb + ' MB 内的文本文件');
  const descriptor = openSync(file, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const data = readFileSync(descriptor);
    if (data.includes(0)) throw httpError(400, '该文件不是文本');
    return data.toString('utf8');
  } finally {
    closeSync(descriptor);
  }
}
export function listFiles(root: string, path?: string) {
  return readdirSync(path ? checkPath(root, path) : root, { withFileTypes: true })
    .filter((item) => item.isFile() || item.isDirectory()).slice(0, fileConfig.listLimit)
    .map((item) => ({ name: item.name, directory: item.isDirectory() }));
}
export function writeText(root: string, path: string, text: string) {
  if (Buffer.byteLength(text) > maxTextFileBytes) throw httpError(400, '文件超过 ' + fileConfig.textMaxMb + ' MB');
  writeWorkspaceFile(root, path, text);
}
export function writeWorkspaceFile(root: string, path: string, content: string | Buffer) {
  const target = checkPath(root, path, true);
  const temp = join(dirname(target), '.write-' + randomUUID());
  try {
    writeFileSync(temp, content, { flag: 'wx' });
    renameSync(temp, target);
  } finally {
    rmSync(temp, { force: true });
  }
}
