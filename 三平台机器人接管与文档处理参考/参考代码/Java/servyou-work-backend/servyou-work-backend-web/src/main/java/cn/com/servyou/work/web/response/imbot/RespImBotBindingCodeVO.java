package cn.com.servyou.work.web.response.imbot;

import lombok.Data;
import lombok.ToString;
import java.time.OffsetDateTime;

/** 一次性绑定文本及有效期响应。 */
@Data
public class RespImBotBindingCodeVO {
    /** 一次性绑定码，仅本次生成时返回。 */
    @ToString.Exclude
    private String code;
    /** 须在对应机器人单聊发送的完整绑定文本。 */
    @ToString.Exclude
    private String bindingText;
    /** 当前绑定码或待发送消息的截止时间。 */
    private OffsetDateTime expiresAt;
}
