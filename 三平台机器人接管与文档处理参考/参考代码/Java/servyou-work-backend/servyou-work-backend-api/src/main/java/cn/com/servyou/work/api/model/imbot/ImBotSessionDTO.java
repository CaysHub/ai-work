package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.time.LocalDateTime;

/** 机器人目标会话信息。 */
@Data
public class ImBotSessionDTO {
    /** 本次消息对应的后端目标会话主键。 */
    private Long targetSessionKey;
    /** 机器人配置主键。 */
    private Long botKey;
    /** 机器人或目标会话显示名称。 */
    private String name;
    /** 会话类型：P2P 单聊，GROUP 群聊。 */
    private String sessionType;
    /** 关联的工作台会话标识。 */
    private String sessionId;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 平台外部会话标识，仅管理端返回。 */
    private String externalChatId;
    /** 最近一次观察到有效用户互动的时间。 */
    private LocalDateTime lastInteractionAt;
    /** 当前用户是否允许向该目标发送。 */
    private Boolean sendable;
}
