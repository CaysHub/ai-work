package cn.com.servyou.work.web.response.imbot;

import lombok.Data;
import java.time.OffsetDateTime;

/** 机器人消息处理及发送状态响应。 */
@Data
public class RespImBotMessageVO {
    /** 机器人消息记录主键。 */
    private String messageId;
    /** 机器人配置主键。 */
    private String botKey;
    /** 平台代码：WECOM 企业微信，FEISHU 飞书。 */
    private String platform;
    /** 消息方向：INBOUND 入站，OUTBOUND 主动发送。 */
    private String direction;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 本次消息对应的后端目标会话主键。 */
    private String targetSessionKey;
    /** 正文类型：TEXT 文本、MARKDOWN 正文、FILE 文档。 */
    private String contentType;
    /** 已保存的输入文档或待发送成果文件主键，以字符串返回。 */
    private String fileId;
    /** 消息正文；绑定控制消息不包含原始绑定码。 */
    private String content;
    /** 从原工作台会话合并的最新回复，包含已返回的思考、正文和待答问题。 */
    private String replyContent;
    /** 业务处理状态，与平台发送状态分别维护。 */
    private String processState;
    /** 平台发送状态，不代表对方已读。 */
    private String sendState;
    /** 关联的工作台会话标识。 */
    private String sessionId;
    /** 一次逻辑请求的稳定标识，重试时复用。 */
    private String requestId;
    /** 原工作台会话实际受理的消息主键。 */
    private String dbMessageId;
    /** 原工作台会话实际受理的运行标识。 */
    private String runId;
    /** 供页面展示的脱敏错误说明。 */
    private String lastError;
    /** 平台确认受理后返回的消息标识。 */
    private String providerMessageId;
    /** 消息创建时间。 */
    private OffsetDateTime createdAt;
    /** 当前绑定码或待发送消息的截止时间。 */
    private OffsetDateTime expiresAt;
    /** 消息最近更新时间。 */
    private OffsetDateTime updatedAt;
}
