# 机器人文档操作

全部通过 `scripts/bot_request.py document.execute` 调用后端。参数为 `botKey`、固定 `action` 和对象 `arguments`；使用官方字段名。后端能力接口列出的 `requiredResourceFields` 必填，`queryFields` 与 `bodyFields` 是可传字段，不代表所有正文参数均可省略。文件上传和导出另传 `sessionId`，上传另传 `conversationFileId`。不要传 URL、请求头、token、执行用户或租户。

飞书能力查询不发平台请求；企微能力查询会获取实时工具目录。`configured=true` 不等于平台权限已开通。真正执行时还会校验当前工作台用户对机器人的授权绑定、机器人启用状态、平台 API 权限及资源权限。

## 飞书：文档

| action | 用途及主要 arguments |
| --- | --- |
| feishu.doc.create | 新建，`title`、可选 `folder_token` |
| feishu.doc.info | 基础信息，`document_id` |
| feishu.doc.read | 纯文本内容，`document_id` |
| feishu.doc.blocks | 内容块分页，`document_id`、`page_size`、`page_token` |
| feishu.doc.convert | Markdown/HTML 转换，`content_type`、`content`；Markdown 的 content_type 为 `markdown` |
| feishu.doc.append | 创建直接子块，`document_id`、`block_id`、`children`、可选 `index` |
| feishu.doc.insert | 创建嵌套块，`document_id`、`block_id`、`children_id`、`descendants`、可选 `index` |
| feishu.doc.update | 批量局部更新，`document_id`、`requests` |
| feishu.doc.delete_blocks | 删除指定范围子块，`document_id`、`block_id`、`start_index`、`end_index`；须用户明确授权 |

写 Markdown 时先转换，再将返回的一级块 ID 和完整块列表对应填入 `children_id`、`descendants`，目标新建文档的根块为实际 `document_id`。不要将所有嵌套块当成直接子块平铺写入；复杂图片或表格按转换结果和平台错误处理。超长内容分段，不承诺一次转换包含任意类型的组件。

用户要求“总结生成飞书文档发我”时，先分析附件，再执行创建、转换、写入和读取核对，成功后返回真实文档链接。创建空文档不等于成果已完成；真人通道仅支持文本回复时，仍可在正文中返回云文档链接。链接以平台返回为准，可用 `feishu.drive.files` 按真实文档标识定位 `url`；未取到链接时说明已完成的步骤和真实文档标识，不拼造地址。权限、分享和覆盖操作继续遵守当前任务授权及后端校验。

读取和局部修改先获取结构及版本。支持版本参数的动作可传 `document_revision_id`，防止覆盖已变动内容；发生版本冲突先重新读取，不盲目重复写入。

## 飞书：电子表格、多维表格和知识库

| action | 用途及主要 arguments |
| --- | --- |
| feishu.sheet.create | 新建，`title`、可选 `folder_token` |
| feishu.sheet.sheets | 查询工作表，`spreadsheet_token` |
| feishu.sheet.read | 范围读取，`spreadsheet_token`、`ranges`，例如 `真实sheetId!A1:C20`；多个范围用逗号分隔或数组 |
| feishu.sheet.write | 范围写入，`spreadsheet_token`、`valueRange:{range,values}` |
| feishu.bitable.create | 新建多维表格，`name`、可选 `folder_token` |
| feishu.bitable.tables | 数据表分页，`app_token` |
| feishu.bitable.table_create | 创建数据表，`app_token`、`table:{name,...}` |
| feishu.bitable.fields | 字段分页，`app_token`、`table_id` |
| feishu.bitable.field_create / field_update | 添加或修改字段，`app_token`、`table_id`、`field_name`、`type`、可选 `property`；修改另传 `field_id` |
| feishu.bitable.records / search_records | 查询记录，`app_token`、`table_id`、分页和筛选；复杂筛选用 search_records 的 `filter` |
| feishu.bitable.add_records | 批量新增，`app_token`、`table_id`、`records:[{fields:{...}}]` |
| feishu.bitable.update_records | 批量更新，`app_token`、`table_id`、`records:[{record_id,fields:{...}}]` |
| feishu.bitable.delete_records | 批量删除，`app_token`、`table_id`、`records:[真实record_id,...]`；须用户明确授权 |
| feishu.wiki.resolve | 解析知识库链接，`token` 为节点标识，可选 `obj_type` |
| feishu.wiki.nodes | 节点分页，`space_id`、可选 `parent_node_token` |

知识库链接先解析，再使用返回的 `obj_type` 和 `obj_token` 选择文档、电子表格或多维表格操作。表格先查询真实工作表 ID；多维表格先查询数据表与字段定义，再按字段类型操作，不能只凭展示名称猜 ID。应用身份能访问的资源由平台授权决定，不是发送人的所有个人文档。

所有分页默认按需读取，`page_size` 最大 100。根据返回的 `has_more/page_token` 继续，不循环拉取无关资源或将所有数据一次性送入上下文。

## 飞书：文件和导入导出

| action | 用途及主要 arguments |
| --- | --- |
| feishu.drive.files | 文件夹列表，`folder_token`、分页；不是全文搜索 |
| feishu.drive.copy | 复制模板，`file_token`、`name`、`type`、`folder_token` |
| feishu.drive.upload | 上传本人会话文件到云空间，`parent_node` 为文件夹标识；另传 `sessionId`、`conversationFileId` |
| feishu.media.upload | 上传文档图片或附件，`parent_type` 只允许 `docx_image` / `docx_file`，`parent_node` 为目标文档；另传本人会话文件参数 |
| feishu.import.create | 建立导入任务，`file_extension`、`file_token`、`type`、`file_name`、`point`；point 使用平台要求的挂载位置 |
| feishu.import.get | 查询导入，`ticket` |
| feishu.export.create | 建立导出，`token`、`type`、`file_extension`、可选 `sub_id` |
| feishu.export.get | 查询导出，`ticket`、`token` |
| feishu.export.download | 下载导出结果并保存到本人会话，`file_token`、带扩展名的 `file_name`；另传 `sessionId` |
| feishu.permission.check | 查询本次机器人应用身份的资源权限，`token`、`type`、`action`，不是任意指定用户的权限 |

上传仅接收工作台文件关联 ID，复用文件所有权校验，不接收本地任意文件路径或下载 URL。单次飞书文件上传最大 20MB。

导入流程：上传 → 创建导入任务 → 间隔至少 10 秒查询 → 返回实际云文档标识与链接。导出流程：创建任务 → 间隔至少 10 秒查询 → 获得导出 `file_token` → 及时下载保存。每轮最多查询 6 次，未完成就保留 ticket 和状态，向用户说明等待；失败立即停止。后端不建立永久轮询。

在线文档常用 DOCX/PDF，电子表格及多维表格按平台支持导出 XLSX/CSV，不假定所有类型都能导出 PDF。导出完成后的下载凭证约 10 分钟有效，及时保存。保存结果返回 `fileId`、`conversationFileId`、`sessionId`、`fileName`。

将已保存的文件发回机器人：

```bash
python3 scripts/bot_request.py message.send --payload '{"botKey":"<机器人ID>","targetSessionKey":"<本人可发送目标>","requestId":"<稳定唯一标识>","contentType":"FILE","sourceSessionId":"<来源会话>","conversationFileId":"<后端返回的会话文件关联ID>"}'
```

附件发送仍经过既有异步发送记录；`PENDING` 不代表已成功发送。企微附件最大 20MB，飞书机器人附件最大 30MB；超限提供已有工作台文件结果，不反复发送。

## 企业微信

企业微信v2文档、普通表格、智能文档及智能表格统一使用 `tool.page` 与 `tool.execute`，`capability=doc`。详细工作流见 [企微七类能力](wecom-tools.md)。实时目录包括 `doc_create`、`doc_contents_get_submit/query`、`doc_contents_overwrite` 等工具，不使用旧版数字doc_type或旧8项工具名称。

文档旧HTTP入口仍可使用 `action=wecom.<平台实际工具名>`，但按需查询工具分页可避免一次加载全部Schema。授权资源范围和读写权限以平台返回为准，不假设只能操作机器人创建的文档，也不假设能读取授权人的全部文件。

## 错误与结果核对

- `IM_BOT_DOCUMENT_NOT_CONFIGURED`：缺少企微文档授权配置，需管理员配置。
- `IM_BOT_DOCUMENT_PERMISSION_DENIED`：权限不足或授权已过期。飞书需核对返回的 scope、应用权限发布状态和目标文档授权；企微需检查 7 天授权。
- `IM_BOT_DOCUMENT_RESULT_UNKNOWN`：HTTP 结果未确认。不要重放创建、追加、批量写入或删除，先核查原文档及任务。
- `IM_BOT_DOCUMENT_FAILED`：平台拒绝或参数不满足要求，保留业务错误码及 logId 用于排查。

不要将创建成功等同于全部内容写入成功，将任务已创建等同于导出已完成，或将平台已受理等同于收件人已读。
