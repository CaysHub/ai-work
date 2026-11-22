package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import lombok.ToString;
import java.util.List;

/** 机器人配置保存参数。 */
@Data
public class ImBotSaveDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 本次修改期望的管理配置版本。 */
    private Long expectedConfigVersion;
    /** 平台代码：WECOM 企业微信，FEISHU 飞书。 */
    private String platform;
    /** 平台应用标识：企业微信 BotID 或飞书 AppID。 */
    private String externalAppId;
    /** 仅写入的平台密钥，修改时省略或为空值则保持原值。 */
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private transient String secret;
    /** 机器人或目标会话显示名称。 */
    private String name;
    /** 机器人用途说明。 */
    private String description;
    /** 是否自动处理普通文本消息。 */
    private Boolean autoProcessEnabled;
    /** 是否启用群聊处理，默认关闭。 */
    private Boolean groupEnabled;
    /** 允许处理消息的外部群会话标识列表。 */
    private List<String> allowedGroupChatIds;
    /** 仅写入的企微文档 MCP 授权地址，省略保留，空字符串清除。 */
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private transient String documentMcpUrl;
    /** 仅写入的企微七类MCP授权地址，类别省略保留、空字符串清除，不返回客户端。 */
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private transient java.util.Map<String,String> mcpUrls;
}
