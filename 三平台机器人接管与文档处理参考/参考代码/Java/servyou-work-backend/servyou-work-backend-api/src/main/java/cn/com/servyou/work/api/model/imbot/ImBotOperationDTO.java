package cn.com.servyou.work.api.model.imbot;

import lombok.Data;

/** 机器人固定操作参数。 */
@Data
public class ImBotOperationDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 本次修改期望的管理配置版本。 */
    private Long expectedConfigVersion;
    /** 机器人消息记录主键。 */
    private Long messageId;
    /** 管理员操作或筛选的目标工作台用户标识。 */
    private String targetUserId;
    /** 操作原因，用于管理审计。 */
    private String reason;
}
