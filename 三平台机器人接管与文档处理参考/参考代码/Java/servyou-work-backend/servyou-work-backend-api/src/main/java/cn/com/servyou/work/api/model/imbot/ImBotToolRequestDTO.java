package cn.com.servyou.work.api.model.imbot;

import lombok.Data;
import java.util.Map;
import java.util.LinkedHashMap;

/** 当前授权用户查询或调用机器人平台工具的参数。 */
@Data
public class ImBotToolRequestDTO {
    /** 机器人配置主键。 */
    private Long botKey;
    /** 官方能力类别：doc、msg、todo、schedule、meeting、disk、contact。 */
    private String capability;
    /** 平台工具名称，只接受当前授权目录实际提供的工具。 */
    private String tool;
    /** 工具名或描述的查询关键词。 */
    private String keyword;
    /** 工具定义分页页码。 */
    private Integer pageNum=1;
    /** 工具定义分页大小，最多二十项。 */
    private Integer pageSize=5;
    /** 按实时工具定义构造的业务参数。 */
    private Map<String,Object> arguments=new LinkedHashMap<>();
    /** 媒体上传来源或下载保存所属的本人会话。 */
    private String sessionId;
    /** 媒体上传来源的本人会话文件关联主键。 */
    private Long conversationFileId;
}
