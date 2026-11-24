import { App } from 'antd';
import { useEffect, useState } from 'react';
import { errorMessage } from '../../shared/types.ts';
import { api } from './api.ts';

export function useRecords<T>(path: string, interval?: number) {
  const { message } = App.useApp();
  const [rows, setRows] = useState<T[]>([]);
  const refresh = () => api<T[]>(path).then(setRows).catch((error) => message.error(errorMessage(error)));
  useEffect(() => {
    let active = true;
    const load = () => api<T[]>(path).then((value) => { if (active) setRows(value); }).catch(() => {});
    load();
    const timer = interval && setInterval(load, interval);
    return () => { active = false; clearInterval(timer); };
  }, [path, interval]);
  return [rows, refresh] as const;
}
