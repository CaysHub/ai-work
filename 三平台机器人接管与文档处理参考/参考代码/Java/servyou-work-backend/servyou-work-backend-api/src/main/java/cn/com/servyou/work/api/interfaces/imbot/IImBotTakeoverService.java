package cn.com.servyou.work.api.interfaces.imbot;

import cn.com.servyou.work.api.model.imbot.*;

/** 真人接管配置与本人授权接口，沿用机器人租户及用户绑定权限。 */
public interface IImBotTakeoverService {
    /** 查询原机器人下指定人员的接管配置，尚未配置时返回空。 */
    ImBotTakeoverDTO findProfile(Long key,String owner);
    /** 为已绑定人员获取或创建独立的接管配置。 */
    ImBotTakeoverDTO profile(Long botKey, String ownerUserId);
    /** 管理员按版本设置接管身份及范围；修改后保持停用，显式启用才处理新消息。 */
    ImBotTakeoverDTO configure(ImBotTakeoverRequestDTO request);
    /** 查询管理租户或接管本人可见的脱敏状态。 */
    ImBotTakeoverDTO status(Long botKey,boolean admin);
    /** 接管本人开始飞书OAuth或钉钉设备授权，授权状态只能消费一次。 */
    ImBotTakeoverDTO startAuthorization(ImBotTakeoverRequestDTO request);
    /** 完成飞书OAuth，或核验钉钉设备授权、企业权限及本人身份。 */
    ImBotTakeoverDTO completeAuthorization(ImBotTakeoverRequestDTO request);
}
