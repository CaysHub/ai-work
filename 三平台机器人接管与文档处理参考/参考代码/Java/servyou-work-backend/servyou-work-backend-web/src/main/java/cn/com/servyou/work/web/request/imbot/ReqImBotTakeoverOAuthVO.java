package cn.com.servyou.work.web.request.imbot;

import lombok.Data;
import lombok.ToString;

/** 接管本人飞书OAuth或钉钉设备授权请求，不接收平台令牌。 */
@Data
public class ReqImBotTakeoverOAuthVO {
    /** 机器人主键。 */
    private String botKey;
    /** 期望配置版本；完成授权使用开始接口返回的新版本。 */
    private String expectedConfigVersion;
    /** 飞书已登记的HTTPS前端回调地址，仅开始授权时使用。 */
    private String redirectUri;
    /** 飞书回调授权码，仅完成授权时使用。 */
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private transient String code;
    /** 飞书原样回传的state，仅完成授权时使用。 */
    @ToString.Exclude
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private transient String state;
}
