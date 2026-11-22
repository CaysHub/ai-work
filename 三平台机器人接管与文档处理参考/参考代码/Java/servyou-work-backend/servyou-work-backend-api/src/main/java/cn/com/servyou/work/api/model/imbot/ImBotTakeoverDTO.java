package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.util.List;

/** 真人接管状态，禁止包含任何平台令牌或MCP地址。 */
@Data
public class ImBotTakeoverDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 当前管理配置版本。 */
    private Long configVersion;
    /** 收发身份模式。 */
    private String identityMode;
    /** 绑定工作台执行账号。 */
    private String ownerUserId;
    /** 已绑定的机器人回调用户标识。 */
    private String externalUserId;
    /** 消息接口中的本人标识：企微为MCP userid，飞书为当前应用open_id。 */
    private String messageUserId;
    /** 已知私聊目标。 */
    private List<String> privateChatIds;
    /** 显式指定群会话。 */
    private List<String> groupChatIds;
    /** 共享群中用于识别纯文本点名的本人名称，平台提供提及标识时优先使用平台标识。 */
    private String groupMentionName;
    /** 是否自动发现会话。 */
    private Boolean discoverEnabled;
    /** 用户令牌或企微消息授权是否已就绪。 */
    private Boolean authorizationReady;
    /** 是否已启用接管。 */
    private Boolean enabled;
    /** 脱敏运行状态或平台能力边界。 */
    private String statusMessage;
    /** 本次OAuth跳转地址，只在开始授权响应出现，不含平台令牌。 */
    private String authorizationUrl;
}
