import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Form, Modal, Select, Space, Switch, Tag } from 'antd';
import { useState } from 'react';
import { chatLimits, scheduleDefaults, scheduleLimits } from '../../shared/config.ts';
import type { Schedule } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { OverflowText, skillLabelPreviews } from '../common/CompactTable.tsx';
import { PreviewInput, PreviewTextArea } from '../common/ContentPreview.tsx';
import { date } from '../common/format.ts';
import { recordRefreshMs } from '../common/records.config.ts';
import { Records, View } from '../common/Records.tsx';
import { Status } from '../common/Status.tsx';
import type { ConnectedView } from '../common/types.ts';
import { scheduleFormConfig } from './schedules.config.ts';
import { useRecords } from '../common/useRecords.ts';

type ScheduleForm = Pick<Schedule, 'name' | 'prompt' | 'skills' | 'cron' | 'tz'> & { enabled: boolean };

export function Schedules({ options, openChat }: ConnectedView) {
  const [rows, refresh] = useRecords<Schedule>('/schedules', recordRefreshMs);
  const { message, modal } = App.useApp();
  const [editing, setEditing] = useState<Partial<Schedule> | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  function edit(row?: Schedule) {
    form.resetFields();
    form.setFieldsValue(row ? { ...row, enabled: !!row.enabled } : { cron: scheduleDefaults.cron, tz: scheduleDefaults.timezone, enabled: scheduleDefaults.enabled, skills: [] });
    setEditing(row ?? {});
  }
  async function save(values: ScheduleForm) {
    if (!editing) return;
    setSaving(true);
    try {
      await api('/schedules' + (editing.id ? '/' + editing.id : ''), editing.id ? 'PUT' : 'POST', values);
      setEditing(null);
      await refresh();
      message.success('定时任务已保存');
    } catch (error) { message.error(errorMessage(error)); }
    finally { setSaving(false); }
  }
  return <View title="定时任务" description="让 AI 按指定时区定期执行提示词，并将结果保存到会话历史。" action={<Button type="primary" icon={<PlusOutlined />} onClick={() => edit()}>新建任务</Button>}>
    <Records rows={rows} columns={[
      { title: '任务', dataIndex: 'name' },
      { title: 'Cron / 时区', render: (_, row) => <><OverflowText text={row.cron}><code>{row.cron}</code></OverflowText><OverflowText text={row.tz}><small>{row.tz}</small></OverflowText></> },
      { title: '下次执行', render: (_, row) => row.enabled ? date(row.next) : <Tag>已停用</Tag> },
      { title: '最近结果', render: (_, row) => row.last_chat ? <Button type="link" onClick={() => { if (row.last_chat) openChat(row.last_chat); }}><Status value={row.last_state} /></Button> : '—' },
      { title: '操作', width: '28%', render: (_, row) => <Space wrap size={0}>
        <Button type="link" onClick={() => edit(row)}>编辑</Button>
        <Button type="link" onClick={async () => { try { await api('/schedules/' + row.id + '/run', 'POST'); await refresh(); message.success('已提交执行'); } catch (error) { message.error(errorMessage(error)); } }}>立即运行</Button>
        <Button type="link" danger onClick={() => modal.confirm({ title: '删除定时任务？', async onOk() { await api('/schedules/' + row.id, 'DELETE'); await refresh(); } })}>删除</Button>
      </Space> },
    ]}>暂无定时任务</Records>
    <Modal title={editing?.id ? '编辑定时任务' : '新建定时任务'} open={!!editing} onCancel={() => setEditing(null)} onOk={() => form.submit()} forceRender confirmLoading={saving}>
      <Form form={form} layout="vertical" onFinish={save} requiredMark={false}>
        <Form.Item name="name" label="任务名称" rules={[{ required: true, max: scheduleLimits.name }]}><PreviewInput /></Form.Item>
        <Form.Item name="prompt" label="提示词" rules={[{ required: true, max: chatLimits.prompt }]}><PreviewTextArea rows={scheduleFormConfig.promptRows} /></Form.Item>
        <Form.Item name="skills" label="选择 Skill"><Select mode="multiple" maxCount={chatLimits.skills} options={options} {...skillLabelPreviews} /></Form.Item>
        <Form.Item name="cron" label="Cron 表达式" extra={`按分钟、小时、日期、月份、星期输入。例如 ${scheduleDefaults.cron}。`} rules={[{ required: true }]}><PreviewInput /></Form.Item>
        <Form.Item name="tz" label="时区" rules={[{ required: true }]}><PreviewInput /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </View>;
}
