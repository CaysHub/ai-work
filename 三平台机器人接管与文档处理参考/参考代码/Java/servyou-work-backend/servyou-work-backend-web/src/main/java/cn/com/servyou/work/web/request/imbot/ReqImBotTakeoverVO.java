package cn.com.servyou.work.web.request.imbot;

import lombok.Data;
import java.util.List;

/** 管理员真人接管配置请求，所有平台凭据沿用后端加密配置。 */
@Data
public class ReqImBotTakeoverVO {
    /** 机器人主键。 */
    private String botKey;
    /** 期望配置版本。 */
    private String expectedConfigVersion;
    /** 身份模式BOT或USER。 */
    private String identityMode;
    /** 已绑定工作台执行账号。 */
    private String ownerUserId;
    /** 与工作台现有绑定一致的机器人回调userid或飞书open_id。 */
    private String externalUserId;
    /** 企微消息MCP中的本人userid，企微真人模式必填；飞书沿用绑定open_id。 */
    private String messageUserId;
    /** 已知私聊标识；企微为对方userid，飞书为p2p chat_id。 */
    private List<String> privateChatIds;
    /** 显式指定的群chat_id。 */
    private List<String> groupChatIds;
    /** 共享群中用于识别纯文本点名的本人名称，平台提供提及标识时优先使用平台标识。 */
    private String groupMentionName;
    /** 是否开启平台支持的自动会话发现。 */
    private Boolean discoverEnabled;
}
