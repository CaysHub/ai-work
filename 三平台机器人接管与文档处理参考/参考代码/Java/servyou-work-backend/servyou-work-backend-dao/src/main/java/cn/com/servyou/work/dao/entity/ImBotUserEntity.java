package cn.com.servyou.work.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

/** 机器人授权用户及外部身份绑定记录。 */
@Data
@TableName("sw_im_bot_user")
public class ImBotUserEntity {
    /** 记录主键。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 机器人配置主键。 */
    private Long botId;
    /** 归属工作台租户标识。 */
    private String tenantId;
    /** 归属工作台用户标识。 */
    private String userId;
    /** 工作台用户显示名称。 */
    private String userName;
    /** 是否已被管理员授予机器人使用权，0 否、1 是。 */
    private Integer authorized;
    /** 当前机器人下的外部用户标识。 */
    private String externalUserId;
    /** 外部用户标识的大小写敏感摘要。 */
    private String externalUserKey;
    /** 一次性绑定码摘要，不保存原始绑定码。 */
    @ToString.Exclude
    private String bindingCodeHash;
    /** 一次性绑定码截止时间。 */
    private LocalDateTime bindingCodeExpiresAt;
    /** 外部身份绑定成功时间。 */
    private LocalDateTime boundAt;
    /** 数据库记录最近更新时间。 */
    private LocalDateTime updateTime;
}
