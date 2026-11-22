import { readFileSync } from 'node:fs';
import { history, wecomEndpoint, wecomGroups } from '../server/platform.ts';

const groupName = process.argv[2]?.trim();
const ownMarker = '接管验证-本人';
const peerMarker = '接管验证-对方';

async function main() {
  if (!groupName || process.stdin.isTTY) {
    throw new Error('请通过 PowerShell 的 Read-Host -MaskInput 输入消息 MCP 授权 URL，并传入测试群的完整名称。');
  }
  const endpoint = readFileSync(0, 'utf8').trim();
  try {
    wecomEndpoint(endpoint, 'msg');
  } catch {
    throw new Error('请使用当前企微账号的消息 MCP 授权 URL。');
  }

  const bot = { platform: 'wecom', secrets: { messageMcp: endpoint } };
  const end = Date.now();
  const start = end - 30 * 60 * 1000;
  let groups;
  try {
    groups = await wecomGroups(bot, start, end);
  } catch {
    throw new Error('企微群列表读取失败，请检查消息 MCP 授权与网络连接。');
  }
  const matching = groups.filter(({ name, id }) => name === groupName || id === groupName);
  if (matching.length === 0) throw new Error('近 30 分钟的群列表里没有找到该群，请核对名称和标记消息。');
  if (matching.length > 1) {
    console.table(matching);
    throw new Error('发现多个同名群，请用上表中的目标群 ID 重新查询。');
  }

  const group = matching[0];
  let messages;
  try {
    messages = await history(bot, group.id, start, end);
  } catch {
    throw new Error('企微群消息读取失败，请检查消息 MCP 是否有该群的读取权限。');
  }

  function senderOf(marker) {
    const senders = new Set(messages.filter((message) => message.text.trim() === marker).map((message) => message.sender));
    return senders.size === 1 ? [...senders][0] : undefined;
  }

  console.log('群聊目标     group:' + group.id);
  const ownId = senderOf(ownMarker);
  const peerId = senderOf(peerMarker);
  if (!ownId || !peerId || ownId === peerId) {
    throw new Error('未能唯一识别两个发送人，请本人和对方各自发送对应标记后重试。');
  }
  console.log('本人消息 ID  ' + ownId);
  console.log('私聊目标     p2p:' + peerId);
}

try {
  await main();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
