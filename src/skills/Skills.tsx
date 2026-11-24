import { UploadOutlined } from '@ant-design/icons';
import { App, Button, Upload } from 'antd';
import { useState } from 'react';
import type { Skill } from '../../shared/types.ts';
import { errorMessage } from '../../shared/types.ts';
import { api } from '../common/api.ts';
import { date } from '../common/format.ts';
import { Records, View } from '../common/Records.tsx';
import { skillUploadAccept } from './skills.config.ts';

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
    <Upload accept={skillUploadAccept} showUploadList={false} beforeUpload={(file) => { upload(file); return false; }}>
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
