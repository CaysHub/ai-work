import type { DocumentSource } from '../../shared/types.ts';

export const sourceLabels: Record<DocumentSource, string> = { upload: '上传', generated: 'AI 生成', workspace: '历史工作区' };
const sourceOptions = [{ value: 'all', label: '全部来源' },
  ...Object.entries(sourceLabels).map(([value, label]) => ({ value, label }))];
export { sourceOptions };
