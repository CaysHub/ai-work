package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.time.LocalDateTime;

/** 机器人及消息分页查询条件。 */
@Data
public class ImBotQueryDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 平台代码：WECOM 企业微信，FEISHU 飞书。 */
    private String platform;
    /** 名称关键字筛选条件。 */
    private String keyword;
    /** 管理员操作或筛选的目标工作台用户标识。 */
    private String targetUserId;
    /** 机器人是否启用，0 否、1 是。 */
    private Integer enabled;
    /** 是否已经完成外部身份绑定。 */
    private Boolean bound;
    /** 是否已被管理员授予机器人使用权，0 否、1 是。 */
    private Integer authorized;
    /** 连接状态：停用、连接中、在线或错误。 */
    private String connectionState;
    /** 会话类型：P2P 单聊，GROUP 群聊。 */
    private String sessionType;
    /** 消息方向：INBOUND 入站，OUTBOUND 主动发送。 */
    private String direction;
    /** 业务处理状态，与平台发送状态分别维护。 */
    private String processState;
    /** 平台发送状态，不代表对方已读。 */
    private String sendState;
    /** 消息创建时间的查询起点。 */
    private LocalDateTime startTime;
    /** 消息创建时间的查询终点。 */
    private LocalDateTime endTime;
    /** 分页页码，从 1 开始。 */
    private Integer pageNum;
    /** 每页条数，默认 20，最大 100。 */
    private Integer pageSize;
}
