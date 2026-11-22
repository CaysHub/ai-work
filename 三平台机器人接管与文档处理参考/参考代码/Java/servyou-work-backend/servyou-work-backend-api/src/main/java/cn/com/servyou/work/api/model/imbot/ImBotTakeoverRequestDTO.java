package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import lombok.ToString;
import java.util.List;

/** 真人接管配置及本人OAuth命令，平台凭据不由调用方传入。 */
@Data
public class ImBotTakeoverRequestDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 期望配置版本。 */
    private Long expectedConfigVersion;
    /** 收发身份：BOT或USER。 */
    private String identityMode;
    /** 已授权并绑定的执行账号。 */
    private String ownerUserId;
    /** 已绑定的机器人回调用户标识：企微userid或当前飞书应用open_id。 */
    private String externalUserId;
    /** 企微消息 MCP 中的本人userid，与机器人绑定标识分别保存；飞书沿用open_id。 */
    private String messageUserId;
    /** 已知私聊：企微填对方userid，飞书填p2p chat_id。 */
    private List<String> privateChatIds;
    /** 额外指定的群会话ID，仍受groupEnabled开关控制。 */
    private List<String> groupChatIds;
    /** 共享群中用于识别纯文本点名的本人名称，平台提供提及标识时优先使用平台标识。 */
    private String groupMentionName;
    /** 是否自动发现活跃群，飞书还可搜索发现私聊。 */
    private Boolean discoverEnabled;
    /** 飞书已登记的前端OAuth回调地址。 */
    private String redirectUri;
    /** 飞书回调的一次性授权码，仅后端交换。 */
    @ToString.Exclude private String code;
    /** 原授权请求的随机状态，用后端密文配置一次性校验。 */
    @ToString.Exclude private String state;
}
