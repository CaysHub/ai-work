package cn.com.servyou.work.api.interfaces.imbot;

import cn.com.servyou.work.api.model.imbot.*;
import com.baomidou.mybatisplus.core.metadata.IPage;

/** 企业微信与飞书共用的配置、授权绑定、目标查询及消息业务接口。 */
public interface IImBotService {
    /** 分页查询当前管理范围或本人已授权机器人；admin 决定采用管理租户或当前用户作用域。 */
    IPage<ImBotDTO> pageBots(ImBotQueryDTO query, boolean admin);
    /** 读取机器人管理详情，指定时返回当前账号有权查看的企微 MCP 原始地址。 */
    ImBotDTO detail(Long botKey, boolean includeMcpUrls);
    /** 分页查询当前登录租户中的应用公共配置摘要。 */
    IPage<ImBotDTO> pageApplications(ImBotQueryDTO query);
    /** 读取当前登录租户中的应用公共配置及完整凭据。 */
    ImBotDTO applicationDetail(Long botKey);
    /** 按配置版本更新应用公共配置。 */
    ImBotDTO updateApplication(ImBotSaveDTO request);
    /** 新增或按配置版本更新机器人；create 为真时默认停用。 */
    ImBotDTO save(ImBotSaveDTO request, boolean create);
    /** 按期望配置版本启用或停用机器人，并处理停用后的未完成消息。 */
    ImBotDTO enable(ImBotOperationDTO request, boolean enabled);
    /** 分页查询管理范围内机器人的授权用户与绑定状态。 */
    IPage<ImBotUserDTO> pageUsers(ImBotQueryDTO query);
    /** 授予或撤销目标用户使用权；撤销时清理绑定及旧目标并取消待处理消息。 */
    void grant(ImBotOperationDTO request, boolean grant);
    /** 准备当前用户的有效门户凭据，生成五分钟一次性绑定码，仅返回原码一次。 */
    ImBotBindingCodeDTO createCode(Long botKey);
    /** 解绑当前用户外部身份并取消相关业务，保留消息与工作台会话历史。 */
    void unbind(Long botKey);
    /** 分页查询当前身份可访问的已互动会话及是否允许发送。 */
    IPage<ImBotSessionDTO> pageSessions(ImBotQueryDTO query, boolean admin);
    /** 分页查询当前管理范围或当前用户的消息处理、发送状态。 */
    IPage<ImBotMessageDTO> pageMessages(ImBotQueryDTO query, boolean admin);
    /** 读取一条可访问消息的正文及状态，普通用户不返回内部运行关联字段。 */
    ImBotMessageDTO messageDetail(Long messageId, boolean admin);
    /** 按当前用户、机器人及 requestId 幂等受理消息，目标须是本人已授权会话。 */
    ImBotMessageDTO send(ImBotSendDTO request);
    /** 取消等待中的消息或请求停止原轮次，admin 为真时沿用真实管理员身份与审计。 */
    ImBotMessageDTO cancel(Long messageId, String reason, boolean admin);
}
