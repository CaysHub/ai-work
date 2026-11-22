# 企业 AI 工作台

工作台源码使用 TypeScript 和 TSX。前端使用 UmiJS 4.7.36、React 19 和 Ant Design 6.7.1，后端由 Node.js 24 直接运行 TypeScript。pi-durable 执行对话和工具调用，SQLite 保存应用状态，服务器磁盘保存 Skill 和产物。

## Windows 11 启动

使用 Node.js 24 或更高版本。PowerShell 中运行以下命令，或者双击项目根目录的 `start-win11.cmd`。

首次配置时把 `.env.example` 复制为项目根目录的 `.env`，并填写 `MODEL_API_KEY`。

```powershell
npm ci
npm run build
npm start
```

打开 [本机工作台](http://127.0.0.1:3400)，首次访问时创建管理员。管理员可在「团队成员」添加账户，每个账户独立管理会话、Skill、任务和机器人。多人部署可以在本机端口前配置 HTTPS 反向代理，并在 `.env` 中设置 `WORKBENCH_PUBLIC_BASE_URL` 和 `WORKBENCH_SECURE_COOKIES=true`。先在服务器本机完成管理员初始化。

在 WebStorm 中把运行目录设为项目根目录。运行 `start` 启动 API。需要前端热更新时，在另一项 npm 配置中运行 `dev`，它通过 Umi 代理访问 API。开发页面默认地址为 [本机 Umi 开发服务](http://127.0.0.1:8000)。

- 默认模型是 DeepSeek V4 Flash，服务地址为 `https://api.deepseek.com`。模型请求使用非思考模式。更换 OpenAI 兼容服务时在 `.env` 修改 `MODEL_ID`、`MODEL_BASE_URL` 和 `MODEL_API_KEY`。 [DeepSeek API 文档](https://api-docs.deepseek.com/)
- 默认数据目录为 `data/`。备份时先停止服务，再复制整个目录。凭据保护密钥随数据目录保管。每个数据目录由一个 Node 进程持有。
- 服务暂停期间错过多个 Cron 时间点时，恢复后合并补跑一次，再按下一个有效时间点执行。

## 会话、Skill 和工具

- 左侧可搜索会话、查看归档记录和打开「文档管理」。可以直接上传，也可以从对话输入框上传附件。对话完成后，本轮生成的文件显示在回复下方，点击文件名从左侧打开预览。
- 「文档管理」按文件名、会话和来源检索，支持下载原文件和查看对应会话。支持 PDF、常见图片、Markdown、文本及 DOCX、XLSX、PPTX 的正文预览。历史工作区文件也会纳入文档库。
- 文档元数据保存在 SQLite，物理文件保存在 `WORKBENCH_DATA_DIR/documents/`。单文件默认上限为 25 MB，可在 `.env` 中修改 `WORKBENCH_DOCUMENT_MAX_MB`。
- 在「Skill 管理」上传 Markdown，或上传含一个 `SKILL.md` 及资源目录的 ZIP。`SKILL.md` 带 YAML 的 `name`、`description`。在输入框、定时任务或机器人设置里引用它。智能体读取选定的 Skill 及其附带文本资源。
- 定时任务使用五段 Cron 和 IANA 时区，默认使用 `Asia/Shanghai`。可立即运行并从最近结果打开会话。
- 管理员通过 `.env` 中的 `MCP_SERVERS` 配置 Streamable HTTP MCP 服务，值为 JSON 数组。每个条目包含 `name`、`url`、`owners`（成员 UUID 数组）、`tools`（工具名称数组）及可选 `apiKey`。会话中的智能体可发现并调用已授权动作。代码执行环境也可通过独立部署的 MCP 服务接入，工作台内置文件工具限于当前会话工作区。

## 机器人与在线文档

在「机器人接入」新建配置，为连接选择 Skill，再启用。应用机器人可配置会话白名单。本人接管支持登记目标会话，私聊以 `p2p:` 开头，群聊以 `group:` 开头，每行一个 ID。群聊只处理明确 @ 本人的来信。同一会话顺序处理，入站去重。员工已回复或发送前无法确认消息历史时跳过回传。左侧「回复记录」可以按来信、回复正文、会话名称和状态查询，包括归档会话。

| 平台 | 应用机器人 | 本人会话接管 | 在线文档 |
| --- | --- | --- | --- |
| 企业微信 | 智能机器人 Bot ID 与 Secret，走官方 WebSocket。 | 填写官方消息 MCP 授权 URL 及本人 userid。默认发现活跃群聊，私聊需批量填对方 userid。 | 填官方文档 MCP 授权 URL。Pi 按问题关键词检索，读取相关文档或在线表格范围。测试资料中的 XLSX 需导入为企微在线表格。 |
| 飞书 | 填写自建应用 App ID 和 App Secret，启用机器人与 `im.message.receive_v1` 长连接事件。 | 填本人 open_id 和目标 chat_id。先保存停用配置，再用「本人授权」绑定，或填已授权的员工访问令牌。飞书后台 OAuth 回调填写 `<WORKBENCH_PUBLIC_BASE_URL>/integrations/feishu/callback`，配置消息历史、本人发送与 offline_access 权限。 | 应用需要文档、Markdown 转块及云空间元数据权限，按需配置有权限的文件夹 Token。 |
| 钉钉 | 填写应用 App ID 与 App Secret，并启用机器人 Stream 消息。白名单私聊填写员工 staff ID，群聊填机器人的 openConversationId。 | 组织在 CLI 设置的 Agent 管理中打开渠道。先保存本人配置与开放钉钉号，再用「本人授权」发起设备绑定并完成确认。聊天目标填 openConversationId。 | 安装官方 DWS CLI，将 `DWS_EXECUTABLE` 设为可执行文件路径。真人接管使用当前连接的本人授权。使用 CLI profile 的连接需在 `DWS_PROFILES` 登记 `{ "name": "profile-name", "owner": "工作台用户 UUID" }` 并填写同名 profile。 |

IM 接管会从来信和会话上下文提取关键词，查证项目事实后回复。飞书按标题匹配配置的文件夹或应用根目录中的新版文档。钉钉通过 DWS 搜索，读取在线文字文档或下载 Excel、Word 后解析正文。可设 `DWS_DOWNLOAD_PROXY` 让钉钉下载命令使用 HTTP 代理，例如 Clash Verge 的 `http://127.0.0.1:7897`。需要报告、纪要或方案时，Pi 生成云文档草稿，由发送流程创建并回传摘要与平台链接。收件人的访问权限由平台和文档所在位置决定。网页会话可读取或创建云文档，也可追加在线文字文档。

企微本人接管在本人私聊发送待办编号。只有一条待办时，可直接发给自己回复正文或处理要求，Pi 会结合来信判断。多条待办时，发「接管待办」查询编号，再发「接管 编号」并附上正文或要求。也可用「接管 最新」指定最新一条，或用「忽略 编号」直接跳过。失败后可发「重试 编号」，Pi 会按状态重新分析、重发已核对正文，或核对企微历史消息。工作台会在本人私聊反馈处理结果。待办有效期为 24 小时。

处理企微本地附件时，先发送文件，再在十分钟内由同一发送人在同一会话发文字要求。当前每个文件最多 5 MB。发送「回传 Word 附件」时，摘要以普通消息发送，生成的 Word 成品以智能机器人文件消息发送。发送「生成企微在线文档」时，原会话收到摘要及文档链接。这两种交付都需要配置企微文档 MCP 授权。

## 验收

门店调拨项目的进度 Excel、Word 文档、台词稿、附件样本及三平台复测步骤见 [验收样本](tests/fixtures/im-acceptance/README.md)。

`npm run typecheck` 运行 TypeScript 严格检查。`npm test` 使用独立的 `.env`、临时 SQLite 和模拟接口，覆盖账号权限、Skill、模型工具调用、Cron、重启后继续对话，以及飞书回传和企微本人确认后回复。真实 DeepSeek 及三平台组织凭据须在目标环境联调。
