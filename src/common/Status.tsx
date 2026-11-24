import { Tag } from 'antd';
import { statuses } from './status.config.ts';

export function Status({ value }: { value?: string | null }) {
  const [label, color] = statuses[value ?? ''] ?? ['—'];
  return <Tag color={color}>{label}</Tag>;
}
