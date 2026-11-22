export async function localApi(name, token, input, signal) {
  const response = await fetch('/api/' + name, {
    method: input === undefined ? 'GET' : 'POST',
    headers: input === undefined ? {} : { 'Content-Type': 'application/json', 'X-Local-Token': token },
    body: input === undefined ? undefined : JSON.stringify(input),
    cache: 'no-store',
    signal,
  });
  let result;
  try { result = await response.json(); }
  catch { throw new Error('本机服务返回异常，请重启后刷新页面'); }
  if (!result.success) {
    const error = new Error(result.message || '请求失败');
    error.steps = result.steps;
    error.code = result.code;
    throw error;
  }
  return result.data;
}

export function botDraft(preset, record, identityMode) {
  const root = preset.platform === 'WECOM' ? record?.rootBot : null;
  const ordinary = root && (identityMode ?? record.activeIdentity?.identityMode) === 'BOT';
  const bot = ordinary
    ? root.identityMode === 'USER' ? { ...root, ...record.application, mcpUrls: record.application?.mcpUrls } : root
    : record?.bot;
  const person = ordinary ? null : record?.takeover;
  return {
    name: bot?.name || preset.name,
    identityMode: identityMode || (ordinary ? 'BOT' : person?.identityMode || bot?.identityMode || 'BOT'),
    autoProcessEnabled: bot?.autoProcessEnabled ?? false,
    groupEnabled: bot?.groupEnabled ?? false,
    allowedGroupChatIds: bot?.allowedGroupChatIds || [],
    grantOwner: true,
    externalUserId: person?.externalUserId || '',
    messageUserId: person?.messageUserId || record?.verifiedMessageUserId || '',
    privateChatIds: (person?.privateChatIds || []).join('\n'),
    groupChatIds: (person?.groupChatIds || []).join('\n'),
    groupMentionName: person?.groupMentionName || '',
    discoverEnabled: person?.discoverEnabled ?? false,
    mcpUrls: { ...bot?.mcpUrls },
    secret: '',
  };
}
