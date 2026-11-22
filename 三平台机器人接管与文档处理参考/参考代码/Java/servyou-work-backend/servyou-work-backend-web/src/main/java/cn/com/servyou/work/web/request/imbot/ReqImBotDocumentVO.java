package cn.com.servyou.work.web.request.imbot;

import lombok.Data;

/** 机器人云文档能力查询和执行请求。 */
@Data
public class ReqImBotDocumentVO {
    /** 机器人配置主键。 */
    private String botKey;
    /** 固定文档操作代码，取能力接口返回值。 */
    private String action;
    /** 操作参数，采用该操作的官方字段名，不接受地址、凭据或执行身份。 */
    private java.util.Map<String,Object> arguments = new java.util.LinkedHashMap<>();
    /** 文件导入来源或导出保存所属的当前用户会话。 */
    private String sessionId;
    /** 文件上传来源的会话文件关联主键，不是平台文件标识。 */
    private String conversationFileId;
}
