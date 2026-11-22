package cn.com.servyou.work.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

/** 机器人配置及多节点归属记录。 */
@Data
@TableName("sw_im_bot")
public class ImBotEntity {
    /** 记录主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 人员接管配置所属的原机器人主键，普通配置为空。 */
    private Long parentBotId;
    /** 归属工作台租户标识。 */
    private String tenantId;
    /** 平台代码：WECOM 企业微信，FEISHU 飞书。 */
    private String platform;
    /** 平台应用标识：企业微信 BotID 或飞书 AppID。 */
    private String externalAppId;
    /** 平台应用标识的大小写敏感摘要，用于唯一约束。 */
    private String appIdKey;
    /** 使用项目统一主密钥加密的平台密钥。 */
    @ToString.Exclude
    private String secretCipher;
    /** 机器人或目标会话显示名称。 */
    private String name;
    /** 机器人用途说明。 */
    private String description;
    /** 机器人是否启用，0 否、1 是。 */
    private Integer enabled;
    /** 是否自动处理普通文本消息，0 否、1 是。 */
    private Integer autoProcessEnabled;
    /** 是否启用群聊处理，默认关闭，0 否、1 是。 */
    private Integer groupEnabled;
    /** 允许处理消息的外部群会话标识列表，以 JSON 数组保存。 */
    private String allowedGroupChatIds;
    /** 管理配置版本，用于防止并发覆盖。 */
    private Long configVersion;
    /** 持有当前机器人连接的后端节点标识。 */
    private String ownerNode;
    /** 节点归属租约截止时间，使用数据库时间。 */
    private LocalDateTime leaseUntil;
    /** 节点归属代次，接管时递增以拒绝旧节点写入。 */
    private Long ownerVersion;
    /** 连接状态：停用、连接中、在线或错误。 */
    private String connectionState;
    /** 后端最近一次登记连接状态的时间。 */
    private LocalDateTime lastHeartbeatAt;
    /** 供页面展示的脱敏错误说明。 */
    private String lastError;
    /** 创建记录的工作台用户标识。 */
    private String createUserId;
    /** 最近修改记录的工作台用户标识。 */
    private String updateUserId;
    /** 数据库记录创建时间。 */
    private LocalDateTime createTime;
    /** 数据库记录最近更新时间。 */
    private LocalDateTime updateTime;
    /** 使用统一主密钥加密的企微机器人文档授权地址，不返回客户端。 */
    @ToString.Exclude
    private String documentConfigCipher;
    /** 收发身份：BOT机器人，USER授权真人。 */
    private String identityMode;
    /** 加密的真人接管配置、用户令牌及发现进度，禁止返回客户端。 */
    @ToString.Exclude
    private String takeoverConfigCipher;
}
