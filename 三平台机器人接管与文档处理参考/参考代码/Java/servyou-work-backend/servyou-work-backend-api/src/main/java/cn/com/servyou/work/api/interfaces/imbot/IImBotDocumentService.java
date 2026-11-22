package cn.com.servyou.work.api.interfaces.imbot;

import cn.com.servyou.work.api.model.imbot.ImBotDocumentDTO;
import cn.com.servyou.work.api.model.imbot.ImBotDocumentRequestDTO;

/** 以已授权机器人身份操作云文档，用户与租户取可信认证上下文。 */
public interface IImBotDocumentService {
    /** 查询文档操作和配置状态；企微实时查询授权工具目录，飞书返回固定接口定义。 */
    ImBotDocumentDTO capabilities(Long botKey);
    /** 执行一个白名单文档操作，导入导出文件沿用已有会话文件权限和存储。 */
    ImBotDocumentDTO execute(ImBotDocumentRequestDTO request);
}
