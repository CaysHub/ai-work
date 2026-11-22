package cn.com.servyou.work.web.request.imbot;

import lombok.Data;

/** 当前用户向已授权目标发送消息的请求。 */
@Data
public class ReqImBotSendVO {
    /** 机器人配置主键。 */
    private String botKey;
    /** 本次消息对应的后端目标会话主键。 */
    private String targetSessionKey;
    /** 一次逻辑请求的稳定标识，重试时复用。 */
    private String requestId;
    /** 正文类型：TEXT 文本、MARKDOWN 或 FILE 附件。 */
    private String contentType;
    /** 消息正文；绑定控制消息不包含原始绑定码。 */
    private String content;
    /** FILE附件来源的本人工作台会话，不改变接收目标。 */
    private String sourceSessionId;
    /** FILE附件的会话文件关联主键，发送前重新验证归属。 */
    private String conversationFileId;
}
