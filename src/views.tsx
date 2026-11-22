import React, { useEffect, useState } from 'react';
import { App, Button, Card, Drawer, Empty, Form, Input, Modal, Select, Space, Switch, Tag, Upload } from 'antd';
import { PlusOutlined, UploadOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import type { Bot, BotActivity, BotConfiguration, BotCredentialKey, BotCredentials, BotMode, Platform, Schedule, Skill, User } from '../shared/types.ts';
import { errorMessage } from '../shared/types.ts';
import type { SkillOption } from './shared.tsx';
import { api, date, Sources, Status } from './shared.tsx';
import { CompactTable, OverflowText, skillLabelPreviews } from './compact-table.tsx';
import { PreviewInput, PreviewTextArea } from './content-preview.tsx';

function View({ title, description, action, children }: { title: string; description: string; action: React.ReactNode; children: React.ReactNode }) {
  return <section className="view"><header className="view-heading"><div><h1>{title}</h1><p>{description}</p></div>{action}</header>{children}</section>;
}
function useRecords<T>(path: string, interval?: number) {
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
function Records<T extends { id: string }>({ rows, columns, children }: {
  rows: T[]; columns: TableProps<T>['columns']; children: React.ReactNode;
}) {
  return <Card className="records">{rows.length ? <CompactTable<T> rowKey="id" dataSource={rows} columns={columns} pagination={{ pageSize: 12, hideOnSinglePage: true }} /> :
    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={children} />}</Card>;
}
export function Skills({ skills, refresh }: { skills: Skill[]; refresh: () => Promise<void> }) {
  const { message, modal } = App.useApp();
  const [uploading, setUploading] = useState(false);
  async function upload(file: File) {
    setUploading(true);
    try {
      const form = new FormData();
      form.append('file', file);
      await api('/skills', 'POST', form);
      await refresh();
      message.success('Skill 已上传');
    } catch (error) { message.error(errorMessage(error)); }
    finally { setUploading(false); }
  }
  return <View title="Skill 管理" description="上传 Skill，并在会话、定时任务和机器人中复用。" action={
    <Upload accept=".md,.zip" showUploadList={false} beforeUpload={(file) => { upload(file); return false; }}>
      <Button type="primary" icon={<UploadOutlined />} loading={uploading}>上传 Skill</Button>
    </Upload>}>
    <Records rows={skills} columns={[
      { title: 'Skill', dataIndex: 'name' },
      { title: '描述', dataIndex: 'description', width: '45%' },
      { title: '上传时间', dataIndex: 'created', render: date },
      { title: '操作', render: (_, row) => <Button type="link" onClick={() => modal.confirm({ title: '归档此 Skill？', async onOk() {
        try { await api('/skills/' + row.id, 'DELETE'); await refresh(); }
        catch (error) { message.error(errorMessage(error)); }
      } })}>归档</Button> },
    ]}>暂无 Skill。上传带 YAML 元数据的 SKILL.md 或 ZIP。</Records>
  </View>;
}

type ConnectedView = { options: SkillOption[]; openChat: (id: string) => void };
type ScheduleForm = Pick<Schedule, 'name' | 'prompt' | 'skills' | 'cron' | 'tz'> & { enabled: boolean };

export function Schedules({ options, openChat }: ConnectedView) {
  const [rows, refresh] = useRecords<Schedule>('/schedules', 3500);
  const { message, modal } = App.useApp();
  const [editing, setEditing] = useState<Partial<Schedule> | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();
  function edit(row?: Schedule) {
    form.resetFields();
    form.setFieldsValue(row ? { ...row, enabled: !!row.enabled } : { cron: '0 9 * * 1-5', tz: 'Asia/Shanghai', enabled: true, skills: [] });
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
        <Form.Item name="name" label="任务名称" rules={[{ required: true, max: 120 }]}><PreviewInput /></Form.Item>
        <Form.Item name="prompt" label="提示词" rules={[{ required: true, max: 24000 }]}><PreviewTextArea rows={4} /></Form.Item>
        <Form.Item name="skills" label="选择 Skill"><Select mode="multiple" maxCount={8} options={options} {...skillLabelPreviews} /></Form.Item>
        <Form.Item name="cron" label="Cron 表达式" extra="按分钟、小时、日期、月份、星期输入。例如 0 9 * * 1-5。" rules={[{ required: true }]}><PreviewInput /></Form.Item>
        <Form.Item name="tz" label="时区" rules={[{ required: true }]}><PreviewInput /></Form.Item>
        <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
      </Form>
    </Modal>
  </View>;
}

const platforms: { value: Platform; label: string; short: string; note: string }[] = [
  { value: 'wecom', label: '企业微信', short: '企', note: '智能机器人长连接与员工消息 MCP' },
  { value: 'feishu', label: '飞书', short: '飞', note: '应用机器人、本人会话与云文档' },
  { value: 'dingtalk', label: '钉钉', short: '钉', note: 'Stream 机器人、本人会话与 DWS 文档' },
];
type BotForm = { name: string; platform: Platform; mode: BotMode; enabled: boolean; secrets?: Partial<Record<Platform, BotCredentials>>;
  targets?: string; employeeId?: string; skills?: string[]; discoverGroups?: boolean; reviewBotId?: string; reviewUserId?: string };
type BotDraft = Partial<BotConfiguration>;
const credentialLabels: Record<BotCredentialKey, string> = {
  appId: 'App ID', appSecret: 'App Secret', botId: 'Bot ID', botSecret: 'Bot Secret',
  messageMcp: '消息 MCP 授权地址', documentMcp: '文档 MCP 授权地址', userToken: '员工访问令牌',
  folderToken: '云空间文件夹 Token', dwsProfile: 'DWS CLI profile',
};

function BotEditor({ row, bots, options, close, saved }: { row: BotDraft; bots: Bot[]; options: SkillOption[]; close: () => void; saved: () => Promise<unknown> }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<BotForm>();
  const [saving, setSaving] = useState(false);
  const platform = Form.useWatch('platform', form) ?? row.platform ?? 'wecom';
  const personal = (Form.useWatch('mode', form) ?? row.mode) === 'personal';
  const reviewBotId = Form.useWatch('reviewBotId', form);
  const reviewRobots = bots.filter((bot) => bot.platform === platform && bot.mode === 'bot' && bot.enabled && bot.id !== row.id);
  const stored = row.platform === platform ? row.secrets : undefined;
  const configFields: BotCredentialKey[] = platform === 'wecom' ?
    [...(!personal || stored?.botId || stored?.botSecret ? ['botId', 'botSecret'] as const : []),
      ...(personal || stored?.messageMcp ? ['messageMcp'] as const : []), 'documentMcp'] :
    [...(platform === 'feishu' || !personal || stored?.appId || stored?.appSecret ? ['appId', 'appSecret'] as const : []),
      ...(personal || stored?.userToken ? ['userToken'] as const : []), platform === 'feishu' ? 'folderToken' : 'dwsProfile'];
  async function save(values: BotForm) {
    setSaving(true);
    try {
      const savedSecrets = values.platform === row.platform ? row.secrets : undefined;
      const secrets = Object.fromEntries(Object.entries(values.secrets?.[values.platform] ?? {}).flatMap(([key, value]) => {
        const previous = savedSecrets?.[key as BotCredentialKey] ?? '';
        const updated = value?.trim() ?? '';
        return updated === previous ? [] : [[key, updated]];
      }));
      const targets = (values.targets ?? '').split(/\r?\n/).map((value) => value.trim()).filter(Boolean).map((value) => ({
        id: value.replace(/^(group|p2p):\s*/i, ''), group: /^group:/i.test(value),
      }));
      await api('/bots' + (row.id ? '/' + row.id : ''), row.id ? 'PUT' : 'POST', {
        name: values.name, platform: values.platform, mode: values.mode, enabled: values.enabled, secrets,
        settings: { targets, employeeId: values.employeeId, skills: values.skills, discoverGroups: values.discoverGroups,
          reviewBotId: values.mode === 'personal' ? values.reviewBotId : undefined,
          reviewUserId: values.mode === 'personal' ? values.reviewUserId?.trim() || undefined : undefined },
      });
      await saved();
      close();
      message.success('连接配置已保存');
    } catch (error) { message.error(errorMessage(error)); }
    finally { setSaving(false); }
  }
  return <Modal open title={row.id ? '编辑机器人连接' : '添加机器人连接'} onCancel={close} onOk={() => form.submit()}
    confirmLoading={saving} width={760}>
    <Form form={form} layout="vertical" onFinish={save} requiredMark={false} initialValues={{
      name: row.name, platform: row.platform ?? 'wecom', mode: row.mode ?? 'bot', enabled: !!row.enabled,
      employeeId: row.settings?.employeeId, skills: row.settings?.skills,
      reviewBotId: row.settings?.reviewBotId, reviewUserId: row.settings?.reviewUserId,
      discoverGroups: row.settings?.discoverGroups ?? true,
      targets: row.settings?.targets.map((target) => (target.group ? 'group:' : 'p2p:') + target.id).join('\n'),
      secrets: row.platform && row.secrets ? { [row.platform]: row.secrets } : undefined,
    }}>
      <div className="form-grid">
        <Form.Item name="name" label="连接名称" rules={[{ required: true, max: 100 }]}><PreviewInput /></Form.Item>
        <Form.Item name="platform" label="平台"><Select options={platforms}
          onChange={() => form.setFieldsValue({ reviewBotId: undefined, reviewUserId: undefined })} /></Form.Item>
      </div>
      <Form.Item name="mode" label="接入模式"><Select options={[{ value: 'bot', label: '应用机器人' }, { value: 'personal', label: '本人会话接管' }]} /></Form.Item>
      {personal && <Form.Item name="employeeId" label="本人消息 ID"
        extra={platform === 'wecom' ? '填写企微消息 MCP 使用的本人 ID。' : undefined}
        rules={[{ required: true }]}><PreviewInput /></Form.Item>}
      {personal && platform === 'wecom' && <Form.Item name="discoverGroups" label="自动发现活跃群聊" valuePropName="checked"><Switch /></Form.Item>}
      {personal && <Form.Item name="reviewBotId" label="接管机器人"
        extra={platform === 'feishu' ? '可自动使用同一飞书应用下已启用的机器人。草稿和指令在机器人私聊中处理。' :
          '选择同平台已启用的应用机器人，在机器人私聊中收草稿和回复指令。'}>
        <Select allowClear options={reviewRobots.map((bot) => ({ label: bot.name, value: bot.id }))}
          placeholder={platform === 'feishu' ? '自动匹配同应用机器人' : '请选择已启用的应用机器人'}
          notFoundContent="请先启用同平台的应用机器人" />
      </Form.Item>}
      {personal && reviewBotId && <Form.Item name="reviewUserId" label="机器人侧本人 ID"
        extra={platform === 'wecom' ? '填写机器人长连接回调中的企微通讯录 UserID。两种 ID 相同时可留空。' :
          platform === 'dingtalk' ? '填写应用机器人收到的员工 staff ID。' : '使用其他飞书应用时，填写本人在该机器人应用下的 open_id。'}
        rules={platform === 'dingtalk' ? [{ required: true }] : undefined}><PreviewInput /></Form.Item>}
      <Form.Item name="targets" label={personal ? '目标会话' : '目标会话白名单（可选）'}
        extra={personal && platform === 'wecom' ? '每行一个 ID，可批量粘贴。私聊填 p2p:对方userid，指定群聊填 group:群ID。' :
          '每行一个会话 ID，个人会话用 p2p:，群会话用 group:。本人接管仅响应明确 @ 本人的群消息。'}>
        <PreviewTextArea rows={2} />
      </Form.Item>
      <div className="form-grid">{configFields.map((key) => {
        const isMcpUrl = key === 'messageMcp' || key === 'documentMcp';
        return <Form.Item key={platform + key} name={['secrets', platform, key]} label={credentialLabels[key]}
          className={isMcpUrl ? 'mcp-url-field' : undefined}>
          <PreviewTextArea rows={isMcpUrl ? 3 : 1} autoSize={isMcpUrl ? false : { minRows: 1, maxRows: 4 }}
            autoComplete="off" spellCheck={false} placeholder="未配置" />
        </Form.Item>;
      })}</div>
      <Form.Item name="skills" label="为机器人选择 Skill"><Select mode="multiple" maxCount={8} options={options} {...skillLabelPreviews} /></Form.Item>
      <Form.Item name="enabled" label="启用连接" valuePropName="checked"><Switch /></Form.Item>
    </Form>
  </Modal>;
}
export function Bots({ options, openChat }: ConnectedView) {
  const [rows, refresh] = useRecords<Bot>('/bots', 3500);
  const [editing, setEditing] = useState<BotDraft | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  const [activity, setActivity] = useState<{ name: string; rows: BotActivity[] } | null>(null);
  const [authorization, setAuthorization] = useState<{ url: string; state?: string; id: string; platform: Platform } | null>(null);
  const [authorizing, setAuthorizing] = useState(false);
  const { message } = App.useApp();
  async function edit(row: Bot) {
    setOpening(row.id);
    try {
      const configuration = await api<BotConfiguration>('/bots/' + row.id + '/configuration');
      setEditing(configuration);
    } catch (error) { message.error(errorMessage(error)); }
    finally { setOpening(null); }
  }
  async function showActivity(row: Bot) {
    try { setActivity({ name: row.name, rows: await api<BotActivity[]>('/bots/' + row.id + '/activity') }); }
    catch (error) { message.error(errorMessage(error)); }
  }
  async function authorize(row: Bot) {
    try {
      const result = await api<{ url: string; state?: string }>('/bots/' + row.id + '/authorize', 'POST');
      setAuthorization({ ...result, id: row.id, platform: row.platform });
    } catch (error) { message.error(errorMessage(error)); }
  }
  async function finishAuthorization() {
    if (!authorization?.state) { setAuthorization(null); await refresh(); return; }
    setAuthorizing(true);
    try {
      await api('/bots/' + authorization.id + '/authorize/finish', 'POST', { state: authorization.state });
      setAuthorization(null);
      await refresh();
      message.success('本人授权已绑定，可以启用接管');
    } catch (error) { message.error(errorMessage(error)); }
    finally { setAuthorizing(false); }
  }
  return <View title="机器人接入" description="连接企业微信、飞书和钉钉，在授权会话中处理消息和文档。" action={<Button type="primary" icon={<PlusOutlined />} disabled={opening !== null} onClick={() => setEditing({})}>添加连接</Button>}>
    <div className="platform-cards">{platforms.map(({ value, label, short, note }) => <button key={value} disabled={opening !== null} onClick={() => setEditing({ platform: value })}>
      <span className={'platform-icon ' + value}>{short}</span><span><b>{label}</b><small>{note}</small></span>
    </button>)}</div>
    <Records rows={rows} columns={[
      { title: '连接', dataIndex: 'name', width: '30%', render: (value, row) => <><OverflowText text={value}><b>{value}</b></OverflowText>{row.detail && <OverflowText text={row.detail}><small>{row.detail}</small></OverflowText>}</> },
      { title: '平台', dataIndex: 'platform', render: (_value, row) => Sources[row.platform] },
      { title: '模式', dataIndex: 'mode', render: (value) => value === 'bot' ? '应用机器人' : '本人接管' },
      { title: '状态', dataIndex: 'status', render: (value) => <Status value={value} /> },
      { title: '操作', width: '29%', render: (_, row) => <Space wrap size={0}><Button type="link" disabled={opening !== null && opening !== row.id} loading={opening === row.id} onClick={() => edit(row)}>编辑</Button>
        {row.mode === 'personal' && row.platform !== 'wecom' && <Button type="link" onClick={() => authorize(row)}>
          {row.configured.includes('userToken') ? '重新授权' : '本人授权'}
        </Button>}
        <Button type="link" onClick={() => showActivity(row)}>消息记录</Button></Space> },
    ]}>暂无机器人连接</Records>
    {editing && <BotEditor row={editing} bots={rows} options={options} close={() => setEditing(null)} saved={refresh} />}
    <Modal title="平台本人授权" open={!!authorization} onCancel={() => setAuthorization(null)} onOk={finishAuthorization}
      okText={authorization?.state ? '完成绑定' : '返回工作台'} confirmLoading={authorizing}>
      {authorization && <p><Button href={authorization.url} target="_blank" rel="noreferrer">打开{Sources[authorization.platform]}授权页</Button></p>}
      <p>请在平台完成授权，再返回工作台。</p>
    </Modal>
    <Drawer title={activity?.name + ' · 消息记录'} open={!!activity} size="large" onClose={() => setActivity(null)}>
      {activity && <Records rows={activity.rows} columns={[
        { title: '会话', width: '37%', render: (_, row) => <Button className="table-text-button" type="link"
          onClick={() => { openChat(row.chat); setActivity(null); }}><OverflowText text={row.target} /></Button> },
        { title: '执行', dataIndex: 'state', render: (value) => <Status value={value} /> },
        { title: '回传', dataIndex: 'reply', render: (value) => <Status value={value} /> },
        { title: '时间', dataIndex: 'created', render: date },
      ]}>暂无消息</Records>}
    </Drawer>
  </View>;
}

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
        <Form.Item name="username" label="用户名" rules={[{ required: true, pattern: /^[a-zA-Z0-9_.-]{3,40}$/ }]}><PreviewInput /></Form.Item>
        <Form.Item name="name" label="显示名称" rules={[{ required: true, max: 40 }]}><PreviewInput /></Form.Item>
        <Form.Item name="password" label="初始密码" rules={[{ required: true, min: 10 }]}><Input.Password autoComplete="new-password" /></Form.Item>
      </Form>
    </Modal>
  </View>;
}
