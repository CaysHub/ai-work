package cn.com.servyou.work.web.response.imbot;

import lombok.Data;
import java.util.Map;

/** 一个授权入口当前提供的平台工具定义。 */
@Data
public class RespImBotToolVO {
    /** 工具所属的固定能力类别。 */
    private String capability;
    /** 执行时使用的真实工具名称。 */
    private String tool;
    /** 平台返回的工具业务说明。 */
    private String description;
    /** 平台实时参数定义，保留嵌套类型和必填项。 */
    private Map<String,Object> inputSchema;
}
