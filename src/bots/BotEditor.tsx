import { App, Form, Modal, Select, Switch } from 'antd';
import { useState } from 'react';
import { botLimits, chatLimits } from '../../shared/config.ts';
import type { Bot, BotConfiguration, BotCredentialKey, BotCredentials, BotMode, Platform } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { skillLabelPreviews } from '../common/CompactTable.tsx';
import { PreviewInput, PreviewTextArea } from '../common/ContentPreview.tsx';
import type { SkillOption } from '../common/types.ts';
import { botEditorConfig, credentialLabels, platforms } from './bots.config.ts';

type BotForm = { name: string; platform: Platform; mode: BotMode; enabled: boolean; secrets?: Partial<Record<Platform, BotCredentials>>;
  targets?: string; employeeId?: string; groupMentionName?: string; skills?: string[]; discoverGroups?: boolean; reviewBotId?: string; reviewUserId?: string };
export type BotDraft = Partial<BotConfiguration>;
export function BotEditor({ row, bots, options, close, saved }: { row: BotDraft; bots: Bot[]; options: SkillOption[]; close: () => void; saved: () => Promise<unknown> }) {
  const { message } = App.useApp();
  const [form] = Form.useForm<BotForm>();
  const [saving, setSaving] = useState(false);
  const platform = Form.useWatch('platform', form) ?? row.platform ?? botEditorConfig.platform;
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
          groupMentionName: values.mode === 'personal' ? values.groupMentionName?.trim() || undefined : undefined,
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
    confirmLoading={saving} width={botEditorConfig.width}>
    <Form form={form} layout="vertical" onFinish={save} requiredMark={false} initialValues={{
      name: row.name, platform: row.platform ?? botEditorConfig.platform, mode: row.mode ?? botEditorConfig.mode, enabled: !!row.enabled,
      employeeId: row.settings?.employeeId, groupMentionName: row.settings?.groupMentionName, skills: row.settings?.skills,
      reviewBotId: row.settings?.reviewBotId, reviewUserId: row.settings?.reviewUserId,
      discoverGroups: row.settings?.discoverGroups ?? botEditorConfig.discoverGroups,
      targets: row.settings?.targets.map((target) => (target.group ? 'group:' : 'p2p:') + target.id).join('\n'),
      secrets: row.platform && row.secrets ? { [row.platform]: row.secrets } : undefined,
    }}>
      <div className="form-grid">
        <Form.Item name="name" label="连接名称" rules={[{ required: true, max: botLimits.name }]}><PreviewInput /></Form.Item>
        <Form.Item name="platform" label="平台"><Select options={platforms}
          onChange={() => form.setFieldsValue({ reviewBotId: undefined, reviewUserId: undefined })} /></Form.Item>
      </div>
      <Form.Item name="mode" label="接入模式"><Select options={[...botEditorConfig.modeOptions]} /></Form.Item>
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
        <PreviewTextArea rows={botEditorConfig.targetRows} />
      </Form.Item>
      {personal && <Form.Item name="groupMentionName" label="群内 @ 名称（可选）"
        extra="填群里显示的本人名称，例如 TigerLand。手动登记的群缺少 @ 成员 ID 时，按正文中的 @ 名称识别。"
        rules={[{ max: botLimits.groupMentionName }]}><PreviewInput placeholder="TigerLand" /></Form.Item>}
      <div className="form-grid">{configFields.map((key) => {
        const isMcpUrl = key === 'messageMcp' || key === 'documentMcp';
        const mcpHelp = isMcpUrl ? botEditorConfig.mcpHelp[key] : undefined;
        return <Form.Item key={platform + key} name={['secrets', platform, key]} label={credentialLabels[key]}
          className={isMcpUrl ? 'mcp-url-field' : undefined} extra={mcpHelp}>
          <PreviewTextArea rows={isMcpUrl ? botEditorConfig.mcpCredentialRows : botEditorConfig.credentialRows}
            autoSize={isMcpUrl ? false : botEditorConfig.credentialAutoSize}
            autoComplete="off" spellCheck={false} placeholder="未配置" />
        </Form.Item>;
      })}</div>
      <Form.Item name="skills" label="为机器人选择 Skill"><Select mode="multiple" maxCount={chatLimits.skills} options={options} {...skillLabelPreviews} /></Form.Item>
      <Form.Item name="enabled" label="启用连接" valuePropName="checked"><Switch /></Form.Item>
    </Form>
  </Modal>;
}
