package cn.com.servyou.work.web.request.imbot;

import lombok.Data;

/** 机器人启停、授权、绑定或消息取消请求。 */
@Data
public class ReqImBotOperationVO {
    /** 机器人配置主键。 */
    private String botKey;
    /** 本次修改期望的管理配置版本。 */
    private String expectedConfigVersion;
    /** 机器人消息记录主键。 */
    private String messageId;
    /** 管理员操作或筛选的目标工作台用户标识。 */
    private String targetUserId;
    /** 操作原因，用于管理审计。 */
    private String reason;
}
