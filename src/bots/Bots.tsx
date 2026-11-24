import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Drawer, Modal, Space } from 'antd';
import { useState } from 'react';
import type { Bot, BotActivity, BotConfiguration, Platform } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { OverflowText } from '../common/CompactTable.tsx';
import { Records, View } from '../common/Records.tsx';
import { Status } from '../common/Status.tsx';
import { api } from '../common/api.ts';
import { date } from '../common/format.ts';
import { recordRefreshMs } from '../common/records.config.ts';
import { Sources } from '../common/status.config.ts';
import type { ConnectedView } from '../common/types.ts';
import { useRecords } from '../common/useRecords.ts';
import type { BotDraft } from './BotEditor.tsx';
import { BotEditor } from './BotEditor.tsx';
import { platforms } from './bots.config.ts';

export function Bots({ options, openChat }: ConnectedView) {
  const [rows, refresh] = useRecords<Bot>('/bots', recordRefreshMs);
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
