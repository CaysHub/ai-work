package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.time.LocalDateTime;

/** 机器人用户授权与绑定信息。 */
@Data
public class ImBotUserDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 工作台用户显示名称。 */
    private String userName;
    /** 是否已被管理员授予机器人使用权。 */
    private Boolean authorized;
    /** 是否已经完成外部身份绑定。 */
    private Boolean bound;
    /** 外部身份绑定成功时间。 */
    private LocalDateTime boundAt;
}
