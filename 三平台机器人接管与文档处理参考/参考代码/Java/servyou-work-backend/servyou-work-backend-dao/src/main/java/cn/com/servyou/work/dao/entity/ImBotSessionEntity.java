package cn.com.servyou.work.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 外部目标与工作台会话映射记录。 */
@Data
@TableName("sw_im_bot_session")
public class ImBotSessionEntity {
    /** 记录主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 机器人配置主键。 */
    private Long botId;
    /** 归属工作台租户标识。 */
    private String tenantId;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 平台外部会话标识，仅管理端返回。 */
    private String externalChatId;
    /** 平台会话标识的大小写敏感摘要。 */
    private String chatKey;
    /** 会话类型：P2P 单聊，GROUP 群聊。 */
    private String sessionType;
    /** 机器人或目标会话显示名称。 */
    private String name;
    /** 关联的工作台会话标识。 */
    private String sessionId;
    /** 最近一次观察到有效用户互动的时间。 */
    private LocalDateTime lastInteractionAt;
    /** 真人会话读取水位、固定分页窗口及待合并消息，仅后端使用。 */
    @lombok.ToString.Exclude
    private String takeoverState;
}
