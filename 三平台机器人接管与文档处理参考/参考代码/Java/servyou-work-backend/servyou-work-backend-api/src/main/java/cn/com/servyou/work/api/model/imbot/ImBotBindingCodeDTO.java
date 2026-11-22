package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.time.LocalDateTime;

/** 机器人一次性绑定码结果。 */
@Data
public class ImBotBindingCodeDTO {
    /** 一次性绑定码，仅本次生成时返回。 */
    private String code;
    /** 须在对应机器人单聊发送的完整绑定文本。 */
    private String bindingText;
    /** 当前绑定码或待发送消息的截止时间。 */
    private LocalDateTime expiresAt;
}
