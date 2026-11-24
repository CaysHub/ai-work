export const filters = [
  ['all', '全部回复'], ['review', '待本人指令'], ['accepted', '已发送'], ['pending', '处理中'],
  ['skipped', '无需处理'], ['rejected', '已忽略'], ['other', '失败或取消'],
].map(([value, label]) => ({ value, label }));

export const replyViewConfig = { detailRefreshMs: 2_500, listRefreshMs: 3_500, detailWidth: 710 };
