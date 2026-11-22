import { randomUUID } from 'node:crypto';
import { constants, lstatSync, mkdirSync, openSync, closeSync, readFileSync, readdirSync, rmSync, writeFileSync, renameSync } from 'node:fs';
import { basename, dirname, join, posix, resolve, sep } from 'node:path';
import AdmZip from 'adm-zip';
import { parse as yaml } from 'yaml';
import type { StoredTables } from './types.ts';
import { config, httpError, now } from './config.ts';
import { get, run } from './store.ts';

const maxFile = 2 * 1024 * 1024;
function parts(relative: unknown) {
  if (typeof relative !== 'string' || !relative || relative.length > 320) throw httpError(400, '文件路径无效');
  const segments = relative.replaceAll('\\', '/').split('/');
  if (segments.some((part) => !part || part === '.' || part === '..' || /[<>:"|?*\u0000-\u001f]/.test(part) ||
    /[ .]$/.test(part) || /^(con|prn|aux|nul|com[0-9¹²³]|lpt[0-9¹²³])(?:\.|$)/i.test(part))) {
    throw httpError(400, '文件路径无效');
  }
  return segments;
}
export function checkPath(root: string, relative: string, createParents = false) {
  const segments = parts(relative);
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
export const workspace = (chat: string) => join(config.dataDir, 'workspaces', chat);
const directory = (id: string) => join(config.dataDir, 'skills', id);

export function uploadSkill(owner: string, file?: Express.Multer.File) {
  if (!file) throw httpError(400, '请选择 SKILL.md 或 ZIP');
  const id = randomUUID();
  const root = directory(id);
  mkdirSync(root, { recursive: true });
  try {
    let files: [string, Buffer][];
    if (/\.zip$/i.test(file.originalname)) {
      const entries = new AdmZip(file.buffer).getEntries().filter((item) => !item.isDirectory);
      const size = entries.reduce((sum, item) => sum + item.header.size, 0);
      if (entries.length > 200 || size > 12 * 1024 * 1024 || entries.some((item) => item.header.size > maxFile || ((item.attr >>> 16) & 0xf000) === 0xa000)) {
        throw httpError(400, 'ZIP 超出文件数量或大小限制，或包含链接');
      }
      const seen = new Set();
      files = entries.map((item) => {
        const name = item.rawEntryName.toString('utf8').replaceAll('\\', '/');
        parts(name);
        if (seen.has(name.toLowerCase())) throw httpError(400, 'ZIP 中有重复路径');
        seen.add(name.toLowerCase());
        return [name, item.getData()];
      });
    } else if (/\.md$/i.test(file.originalname)) {
      files = [['SKILL.md', file.buffer]];
    } else {
      throw httpError(400, '支持 Markdown 或 ZIP');
    }
    const entries = files.filter(([name]) => basename(name).toUpperCase() === 'SKILL.MD');
    if (entries.length !== 1) throw httpError(400, '每个压缩包需要且只允许一个 SKILL.md');
    const [entry, bytes] = entries[0];
    const text = bytes.toString('utf8').replace(/^\uFEFF/, '');
    const metadata = text.match(/^---\r?\n([\s\S]+?)\r?\n---(?:\r?\n|$)/);
    const header = metadata ? yaml(metadata[1], { maxAliasCount: 0 }) : {};
    const name = header?.name;
    const description = header?.description;
    if (typeof name !== 'string' || !name.trim() || name.length > 120 || typeof description !== 'string' || !description.trim() || description.length > 1000 || text.length > 32000) {
      throw httpError(400, 'SKILL.md 需要有效的 YAML name 和 description，且正文总长度不超过 32,000 字符');
    }
    for (const [path, content] of files) writeFileSync(checkPath(root, path, true), content, { flag: 'wx' });
    run('INSERT INTO skills (id,owner,name,description,entry,created) VALUES (?,?,?,?,?,?)', id, owner, name.trim(), description.trim(), entry, now());
    return { id, name, description };
  } catch (error) {
    rmSync(root, { recursive: true, force: true });
    if (error instanceof Error && 'status' in error) throw error;
    throw httpError(400, '无法读取 Skill 文件，请检查 ZIP 和 YAML 格式');
  }
}
export function skillRows(owner: string, ids: string[], includeArchived = false) {
  return ids.map((id) => {
    const row = get<StoredTables['skills']>('SELECT * FROM skills WHERE owner=? AND id=? AND (archived=0 OR ?)', owner, id, Number(includeArchived));
    if (!row) throw httpError(400, '所选 Skill 已归档或无权使用');
    return row;
  });
}
export function skillInstructions(owner: string, ids: string[]) {
  return skillRows(owner, ids, true).map(({ id, name, entry }) => {
    const root = directory(id);
    const body = readFileSync(checkPath(root, entry), 'utf8');
    return 'Skill ' + name + '，资源 ID ' + id + '\n' + body;
  }).join('\n\n');
}
export function skillResource(owner: string, ids: string[], id: string, path: string) {
  if (!ids.includes(id)) throw httpError(403, '本轮未选用该 Skill');
  const row = skillRows(owner, [id], true)[0];
  return readText(directory(id), posix.join(posix.dirname(row.entry), ...parts(path)));
}
export function readText(root: string, path: string) {
  const file = checkPath(root, path);
  const info = lstatSync(file);
  if (!info.isFile() || info.size > maxFile) throw httpError(400, '只支持 2 MB 内的文本文件');
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
    .filter((item) => item.isFile() || item.isDirectory()).slice(0, 500)
    .map((item) => ({ name: item.name, directory: item.isDirectory() }));
}
export function writeText(root: string, path: string, text: string) {
  if (Buffer.byteLength(text) > maxFile) throw httpError(400, '文件超过 2 MB');
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
