package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 机器人配置及当前用户可用状态。 */
@Data
public class ImBotDTO {
    /** 收发身份：BOT机器人或USER授权真人。 */
    private String identityMode;
    /** 机器人配置主键。 */
    private Long botKey;
    /** 归属工作台租户标识。 */
    private String tenantId;
    /** 平台代码：WECOM 企业微信，FEISHU 飞书。 */
    private String platform;
    /** 平台应用标识：企业微信 BotID 或飞书 AppID。 */
    private String externalAppId;
    /** 机器人或目标会话显示名称。 */
    private String name;
    /** 机器人用途说明。 */
    private String description;
    /** 是否已经配置平台密钥，响应不返回密钥内容。 */
    private Boolean hasSecret;
    /** 机器人是否启用。 */
    private Boolean enabled;
    /** 是否自动处理普通文本消息。 */
    private Boolean autoProcessEnabled;
    /** 是否启用群聊处理，默认关闭。 */
    private Boolean groupEnabled;
    /** 允许处理消息的外部群会话标识列表。 */
    private List<String> allowedGroupChatIds;
    /** 管理配置版本，用于防止并发覆盖。 */
    private Long configVersion;
    /** 连接状态：停用、连接中、在线或错误。 */
    private String connectionState;
    /** 持有当前机器人连接的后端节点标识。 */
    private String ownerNode;
    /** 后端最近一次登记连接状态的时间。 */
    private LocalDateTime lastHeartbeatAt;
    /** 供页面展示的脱敏错误说明。 */
    private String lastError;
    /** 是否已被管理员授予机器人使用权。 */
    private Boolean authorized;
    /** 是否已经完成外部身份绑定。 */
    private Boolean bound;
    /** 当前机器人不可用的展示原因。 */
    private String unavailableReason;
    /** 当前平台支持的主动发送正文类型。 */
    private List<String> supportedContentTypes;
    /** 管理详情中显示文档必要配置是否齐全，不代表平台权限已开通。 */
    private Boolean documentConfigured;
    /** 已配置的企微能力类别，不代表授权仍有效或具备全部资源权限。 */
    private java.util.List<String> configuredCapabilities;
    /** 管理详情显式读取时返回的企微 MCP 原始授权地址，按能力代码区分。 */
    @lombok.ToString.Exclude
    private Map<String, String> mcpUrls;
    /** 应用配置详情中的平台密钥原值。 */
    @lombok.ToString.Exclude
    private String secret;
    /** 同一个企微 BotID 当前启用的配置主键。 */
    private Long activeBotKey;
    /** 当前启用配置的版本，用于停用时校验。 */
    private Long activeBotConfigVersion;
    /** 当前启用的企微身份代码。 */
    private String activeIdentityMode;
    /** 管理详情中的当前接管账号。 */
    private String activeOwnerUserId;
}
