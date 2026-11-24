import AdmZip from 'adm-zip';
import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { basename, join, posix } from 'node:path';
import { parse as yaml } from 'yaml';
import { bytesPerMiB } from '../../shared/config.ts';
import { httpError, now } from '../common/runtime.ts';
import type { StoredTables } from '../common/types.ts';
import { config } from '../config/env.ts';
import { maxTextFileBytes } from '../files/files.config.ts';
import { checkPath, pathParts, readText } from '../files/workspace.ts';
import { get, run } from '../storage/store.ts';
import { skillConfig } from './skills.config.ts';

const directory = (id: string) => join(config.dataDir, skillConfig.directory, id);

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
      if (entries.length > skillConfig.archiveFileLimit || size > skillConfig.archiveMaxMb * bytesPerMiB || entries.some((item) => item.header.size > maxTextFileBytes || ((item.attr >>> 16) & 0xf000) === 0xa000)) {
        throw httpError(400, 'ZIP 超出文件数量或大小限制，或包含链接');
      }
      const seen = new Set();
      files = entries.map((item) => {
        const name = item.rawEntryName.toString('utf8').replaceAll('\\', '/');
        pathParts(name);
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
    if (typeof name !== 'string' || !name.trim() || name.length > skillConfig.nameLength || typeof description !== 'string' || !description.trim() || description.length > skillConfig.descriptionLength || text.length > skillConfig.markdownLength) {
      throw httpError(400, 'SKILL.md 需要有效的 YAML name 和 description，且正文总长度不超过 ' + skillConfig.markdownLength.toLocaleString('en-US') + ' 字符');
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
  return readText(directory(id), posix.join(posix.dirname(row.entry), ...pathParts(path)));
}
