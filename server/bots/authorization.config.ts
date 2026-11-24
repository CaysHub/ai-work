export const authOptions = {
  maxTokenSeconds: 365 * 86_400,
  refreshMs: 120_000,
  feishuFlowMs: 300_000,
  dingFlowMs: 900_000,
  dingDeviceGrant: 'urn:ietf:params:oauth:grant-type:device_code',
  feishuScopes: 'im:message im:message:readonly im:message.p2p_msg:get_as_user im:message.group_msg:get_as_user im:message.send_as_user offline_access',
};
