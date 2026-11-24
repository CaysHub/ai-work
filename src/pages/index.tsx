import { Alert, App as AntApp, ConfigProvider, Spin } from 'antd';
import 'antd/dist/reset.css';
import zhCN from 'antd/locale/zh_CN';
import { useEffect, useState } from 'react';
import type { User } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { Account } from '../account/Account.tsx';
import { api } from '../common/api.ts';
import '../common/app.less';
import { Workspace } from '../workspace/Workspace.tsx';
import { theme } from '../workspace/theme.config.ts';

type AuthStatus = { user: User | null; setup: boolean };

function Root() {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { api<AuthStatus>('/auth/status').then(setStatus).catch((failure: unknown) => setError(errorMessage(failure))); }, []);
  if (error) return <div className="center"><Alert type="error" showIcon title={error} /></div>;
  if (!status) return <div className="center"><Spin size="large" /></div>;
  if (!status.user) return <Account setup={status.setup} login={(user) => setStatus({ user, setup: false })} />;
  return <Workspace user={status.user} logout={async () => { await api('/auth/logout', 'POST'); setStatus({ user: null, setup: false }); }} />;
}

export default function WorkbenchPage() {
  return <ConfigProvider locale={zhCN} theme={theme}><AntApp><Root /></AntApp></ConfigProvider>;
}
