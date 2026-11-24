import { ArrowRightOutlined, UserOutlined } from '@ant-design/icons';
import { App as AntApp, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { accountLimits } from '../../shared/config.ts';
import type { User } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { PreviewInput } from '../common/ContentPreview.tsx';
import { Brand } from '../workspace/Brand.tsx';

type Credentials = { username: string; password: string; name?: string };

export function Account({ setup, login }: { setup: boolean; login: (user: User) => void }) {
  const { message } = AntApp.useApp();
  const [busy, setBusy] = useState(false);
  async function submit(values: Credentials) {
    setBusy(true);
    try { login(await api<User>('/auth/' + (setup ? 'setup' : 'login'), 'POST', values)); }
    catch (error) { message.error(errorMessage(error)); }
    finally { setBusy(false); }
  }
  return <div className="login-layout">
    <section className="login-brand"><Brand /><div><h1>把日常工作<br />交给 AI 助手</h1><p>会话、Skill、定时任务和机器人集中管理。</p><div className="login-orbit"><i /><i /><i /></div></div></section>
    <section className="login-main"><div className="login-card">
      <h2>{setup ? '初始化工作台' : '登录工作台'}</h2>
      <p>{setup ? '创建管理员账户，随后可邀请团队成员。' : '使用你的工作台账号登录。'}</p>
      <Form layout="vertical" onFinish={submit} requiredMark={false}>
        <Form.Item name="username" label="用户名" rules={[{ required: true, pattern: accountLimits.usernamePattern, message: '输入 3 至 40 位字母、数字或 . _ -' }]}>
          <PreviewInput autoComplete="username" prefix={<UserOutlined />} />
        </Form.Item>
        {setup && <Form.Item name="name" label="显示名称" rules={[{ required: true, max: accountLimits.displayName }]}><PreviewInput /></Form.Item>}
        <Form.Item name="password" label="密码" rules={[{ required: true, min: accountLimits.passwordMin, message: '密码至少 10 位' }]}>
          <Input.Password autoComplete={setup ? 'new-password' : 'current-password'} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={busy} block>{setup ? '创建管理员' : '登录'} <ArrowRightOutlined /></Button>
      </Form>
    </div></section>
  </div>;
}
