package cn.com.servyou.work.web.response.imbot;

import lombok.Data;

/** 机器人云文档能力或执行结果。 */
@Data
public class RespImBotDocumentVO {
    /** 当前机器人所属平台。 */
    private String platform;
    /** 是否具备本地必要配置；不代表平台已授予全部资源权限。 */
    private Boolean configured;
    /** 未配置或授权受限时的处理说明。 */
    private String notice;
    /** 当前平台固定操作代码列表。 */
    private java.util.List<String> actions;
    /** 操作结果或各操作所需参数定义，不包含认证凭据。 */
    private java.util.Map<String,Object> data;
}
