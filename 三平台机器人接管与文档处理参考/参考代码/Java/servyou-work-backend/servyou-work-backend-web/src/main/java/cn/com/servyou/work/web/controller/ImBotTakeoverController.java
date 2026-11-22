package cn.com.servyou.work.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.interfaces.imbot.IImBotTakeoverService;
import cn.com.servyou.work.api.model.CommonResult;
import cn.com.servyou.work.core.aspect.TenantIsolatedEndpoint;
import cn.com.servyou.work.web.converter.*;
import cn.com.servyou.work.web.request.imbot.ReqImBotTakeoverOAuthVO;
import cn.com.servyou.work.web.response.imbot.RespImBotTakeoverVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 接管本人授权入口，沿用工作台登录，不开放匿名回调或平台令牌提交入口。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/im/bot/takeover")
@RequiredArgsConstructor
public class ImBotTakeoverController {
    /** 真人接管配置与本人OAuth服务。 */
    private final IImBotTakeoverService service;
    /** 查询当前工作台用户自身的接管配置。 */
    @GetMapping("/detail")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotTakeoverVO> detail(@RequestParam String botKey){return CommonResult.success(ImBotWebConverter.toVO(service.status(WebIdConverter.toLong(botKey,"botKey"),false)));}
    /** 获取飞书OAuth或钉钉设备授权地址，返回完成授权所需的新配置版本。 */
    @PostMapping("/oauth/start")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotTakeoverVO> start(@RequestBody ReqImBotTakeoverOAuthVO vo){return CommonResult.success(ImBotWebConverter.toVO(service.startAuthorization(ImBotWebConverter.toDTO(vo))));}
    /** 飞书提交回调code和state，钉钉确认后提交配置版本供后端查询授权结果。 */
    @PostMapping("/oauth/complete")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotTakeoverVO> complete(@RequestBody ReqImBotTakeoverOAuthVO vo){return CommonResult.success(ImBotWebConverter.toVO(service.completeAuthorization(ImBotWebConverter.toDTO(vo))));}
}
