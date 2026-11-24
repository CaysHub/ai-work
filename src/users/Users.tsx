import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Form, Input, Modal, Tag } from 'antd';
import { useState } from 'react';
import { accountLimits } from '../../shared/config.ts';
import type { User } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { PreviewInput } from '../common/ContentPreview.tsx';
import { date } from '../common/format.ts';
import { Records, View } from '../common/Records.tsx';
import { useRecords } from '../common/useRecords.ts';

export function Users() {
  const [rows, refresh] = useRecords<User & { created: number }>('/users');
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const { message } = App.useApp();
  return <View title="团队成员" description="添加工作台账户。各成员独立管理自己的会话、Skill、任务和连接。" action={<Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setOpen(true); }}>添加成员</Button>}>
    <Records rows={rows} columns={[
      { title: '用户名', dataIndex: 'username' }, { title: '显示名称', dataIndex: 'name' },
      { title: '角色', dataIndex: 'role', render: (role) => <Tag color={role === 'admin' ? 'green' : undefined}>{role === 'admin' ? '管理员' : '成员'}</Tag> },
      { title: '创建时间', dataIndex: 'created', render: date },
    ]}>暂无成员</Records>
    <Modal title="添加团队成员" open={open} onCancel={() => setOpen(false)} onOk={() => form.submit()}>
      <Form form={form} layout="vertical" onFinish={async (values) => {
        try { await api('/users', 'POST', values); setOpen(false); await refresh(); message.success('成员已添加'); }
        catch (error) { message.error(errorMessage(error)); }
      }}>
        <Form.Item name="username" label="用户名" rules={[{ required: true, pattern: accountLimits.usernamePattern }]}><PreviewInput /></Form.Item>
        <Form.Item name="name" label="显示名称" rules={[{ required: true, max: accountLimits.displayName }]}><PreviewInput /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, min: accountLimits.passwordMin }]}><Input.Password autoComplete="new-password" /></Form.Item>
      </Form>
    </Modal>
  </View>;
}
