package cn.com.servyou.work.web.controller.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.interfaces.imbot.IImBotTakeoverService;
import cn.com.servyou.work.api.model.CommonResult;
import cn.com.servyou.work.api.model.imbot.ImBotTakeoverDTO;
import cn.com.servyou.work.core.aspect.*;
import cn.com.servyou.work.web.converter.*;
import cn.com.servyou.work.web.request.imbot.ReqImBotTakeoverVO;
import cn.com.servyou.work.web.response.imbot.RespImBotTakeoverVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 管理员真人接管配置，沿用管理权限及X-Admin-Tenant-Id租户边界。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/admin/im/bot/takeover")
@RequiredArgsConstructor
public class AdminImBotTakeoverController {
    /** 真人接管配置与用户授权服务。 */
    private final IImBotTakeoverService service;
    /** 按工作台账号查询接管状态。 */
    @GetMapping("/profile/detail")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResult<RespImBotTakeoverVO> profileDetail(@RequestParam String botKey,@RequestParam String ownerUserId) {
        ImBotTakeoverDTO value = service.findProfile(WebIdConverter.toLong(botKey,"botKey"),ownerUserId);
        return CommonResult.success(value==null?null:ImBotWebConverter.toVO(value));
    }
    /** 为指定人员打开接管配置，返回该人员后续操作使用的主键。 */
    @PostMapping("/profile")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotTakeoverVO> profile(@RequestBody ReqImBotTakeoverVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.profile(WebIdConverter.toLong(vo.getBotKey(),"botKey"), vo.getOwnerUserId())));
    }
    /** 设置真人身份和会话范围；保存后停用，通过原enable接口显式启用。 */
    @PostMapping("/update")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotTakeoverVO> update(@RequestBody ReqImBotTakeoverVO vo){return CommonResult.success(ImBotWebConverter.toVO(service.configure(ImBotWebConverter.toDTO(vo))));}
    /** 查看当前管理租户的接管状态，不返回令牌或MCP密钥。 */
    @GetMapping("/detail")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResult<RespImBotTakeoverVO> detail(@RequestParam String botKey){return CommonResult.success(ImBotWebConverter.toVO(service.status(WebIdConverter.toLong(botKey,"botKey"),true)));}
}
