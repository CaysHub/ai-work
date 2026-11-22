package cn.com.servyou.work.web.response.imbot;

import lombok.Data;
import java.util.List;

/** 真人接管脱敏状态，不包含令牌及授权配置密文。 */
@Data
public class RespImBotTakeoverVO {
    /** 机器人配置主键。 */
    private String botKey;
    /** 配置版本。 */
    private String configVersion;
    /** 收发身份模式。 */
    private String identityMode;
    /** 绑定工作台账号。 */
    private String ownerUserId;
    /** 与工作台现有绑定一致的机器人回调用户标识。 */
    private String externalUserId;
    /** 消息接口中的本人标识，企微MCP userid与绑定userid分开，未配置时为空字符串。 */
    private String messageUserId;
    /** 已知私聊目标。 */
    private List<String> privateChatIds;
    /** 指定群会话。 */
    private List<String> groupChatIds;
    /** 共享群中用于识别纯文本点名的本人名称，平台提供提及标识时优先使用平台标识。 */
    private String groupMentionName;
    /** 是否自动发现会话。 */
    private Boolean discoverEnabled;
    /** 平台本人授权是否已就绪。 */
    private Boolean authorizationReady;
    /** 接管是否启用。 */
    private Boolean enabled;
    /** 脱敏状态或已确认的平台能力边界。 */
    private String statusMessage;
    /** 开始OAuth时的一次性跳转链接，页面须由用户主动打开。 */
    private String authorizationUrl;
}
