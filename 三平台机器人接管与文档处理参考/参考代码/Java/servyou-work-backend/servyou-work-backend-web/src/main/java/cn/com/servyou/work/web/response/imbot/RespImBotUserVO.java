package cn.com.servyou.work.web.response.imbot;

import lombok.Data;
import java.time.OffsetDateTime;

/** 机器人授权用户及绑定状态响应。 */
@Data
public class RespImBotUserVO {
    /** 机器人配置主键。 */
    private String botKey;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 工作台用户显示名称。 */
    private String userName;
    /** 是否已被管理员授予机器人使用权。 */
    private Boolean authorized;
    /** 是否已经完成外部身份绑定。 */
    private Boolean bound;
    /** 外部身份绑定成功时间。 */
    private OffsetDateTime boundAt;
}
