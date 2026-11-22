package cn.com.servyou.work.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

/** 入站处理结果及主动发送状态记录。 */
@Data
@TableName("sw_im_bot_message")
public class ImBotMessageEntity {
    /** 记录主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 机器人配置主键。 */
    private Long botId;
    /** 归属工作台租户标识。 */
    private String tenantId;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 本次消息对应的后端目标会话主键。 */
    private Long targetSessionId;
    /** 消息方向：INBOUND 入站，OUTBOUND 主动发送。 */
    private String direction;
    /** 正文类型：TEXT 文本、MARKDOWN 正文、FILE 文档。 */
    private String contentType;
    /** 已保存的输入文档或待发送成果文件主键，关联 sw_file.id。 */
    private Long fileId;
    /** 消息正文；绑定控制消息不包含原始绑定码。 */
    @ToString.Exclude
    private String content;
    /** 从原工作台会话合并的最新回复，包含已返回的思考、正文和待答问题。 */
    @ToString.Exclude
    private String replyContent;
    /** 平台入站消息标识的摘要，用于消息去重。 */
    private String inboundKey;
    /** 平台原始入站消息标识。 */
    private String externalMessageId;
    /** 平台回复所需的连接标识与请求上下文，仅后端使用。 */
    @ToString.Exclude
    private String replyContext;
    /** 固定的平台发送请求标识，用于平台防重。 */
    private String providerUuid;
    /** 平台确认受理后返回的消息标识。 */
    private String providerMessageId;
    /** 一次逻辑请求的稳定标识，重试时复用。 */
    private String requestId;
    /** 主动发送的目标和正文摘要，用于检测幂等冲突。 */
    private String requestHash;
    /** 业务处理状态，与平台发送状态分别维护。 */
    private String processState;
    /** 平台发送状态，不代表对方已读。 */
    private String sendState;
    /** 关联的工作台会话标识。 */
    private String sessionId;
    /** 原工作台会话实际受理的消息主键。 */
    private Long dbMessageId;
    /** 原工作台会话实际受理的运行标识。 */
    private String runId;
    /** 已观察的原会话事件序号。 */
    private Integer lastSeq;
    /** 供页面展示的脱敏错误说明。 */
    private String lastError;
    /** 最近处理该消息的机器人归属代次。 */
    private Long processingOwnerVersion;
    /** 当前绑定码或待发送消息的截止时间。 */
    private LocalDateTime expiresAt;
    /** 数据库记录创建时间。 */
    private LocalDateTime createTime;
    /** 数据库记录最近更新时间。 */
    private LocalDateTime updateTime;
}
