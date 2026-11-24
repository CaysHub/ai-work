export type DocumentArgs = { platform: string; action: string; botId?: string; documentId?: string; title?: string; content?: string };
export type DocumentMetadata = { owner: string; documentId: string; title: string };

export function identifier(value: unknown) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,250}$/.test(value)) throw new Error('文档 ID 无效');
  return value;
}
