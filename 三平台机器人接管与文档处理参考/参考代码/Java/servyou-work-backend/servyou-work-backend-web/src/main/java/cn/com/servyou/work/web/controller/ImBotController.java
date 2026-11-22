package cn.com.servyou.work.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.interfaces.imbot.IImBotService;
import cn.com.servyou.work.api.model.CommonResult;
import cn.com.servyou.work.api.model.CommonResultPage;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.web.request.imbot.*;
import cn.com.servyou.work.web.response.imbot.*;
import cn.com.servyou.work.web.converter.ImBotWebConverter;
import cn.com.servyou.work.web.converter.WebIdConverter;
import cn.com.servyou.work.core.aspect.TenantIsolatedEndpoint;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/** 企业机器人用户接口，权限取当前认证用户及业务租户。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/im/bot")
@RequiredArgsConstructor
public class ImBotController {
    /** 统一机器人配置、授权及消息业务服务。 */
    private final IImBotService service;
    /** 复用现有用户单点登录环境保存能力。 */
    private final cn.com.servyou.work.core.manager.runtimeenv.IRuntimeEnvCookieManager runtimeEnvCookieManager;
    /** 分页查询当前管理范围或本人已授权的机器人。 */
    @PostMapping("/page")
    @TenantIsolatedEndpoint
    public CommonResultPage<RespImBotVO> page(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotDTO> page=service.pageBots(ImBotWebConverter.toDTO(vo),false);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 分页展示当前登录租户的应用公共配置。 */
    @PostMapping("/application/page")
    @TenantIsolatedEndpoint
    public CommonResultPage<RespImBotVO> applications(@RequestBody(required = false) ReqImBotQueryVO vo) {
        IPage<ImBotDTO> page = service.pageApplications(ImBotWebConverter.toDTO(vo));
        return CommonResultPage.success(page, page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 读取当前登录租户的应用公共配置原值。 */
    @GetMapping("/application/detail")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> applicationDetail(@RequestParam String botKey, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return CommonResult.success(ImBotWebConverter.toVO(service.applicationDetail(WebIdConverter.toLong(botKey, "botKey"))));
    }
    /** 按版本更新当前登录租户的应用公共配置。 */
    @PostMapping("/application/update")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> updateApplication(@RequestBody ReqImBotSaveVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.updateApplication(ImBotWebConverter.toDTO(vo))));
    }
    /** 生成当前用户的一次性绑定文本，并保存已有单点登录环境。 */
    @PostMapping("/binding/create-code")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotBindingCodeVO> createCode(@RequestBody ReqImBotOperationVO vo, javax.servlet.http.HttpServletRequest request) {
        runtimeEnvCookieManager.captureSsoCookieEnv(cn.com.servyou.work.core.utils.UserInfoUtil.getTenantId(),
                cn.com.servyou.work.core.utils.UserInfoUtil.getUserId(), cn.com.servyou.work.web.converter.ConversationEnvWebConverter.extractSsoCookieEnv(request));
        return CommonResult.success(ImBotWebConverter.toVO(service.createCode(ImBotWebConverter.toDTO(vo).getBotKey())));
    }
    /** 解绑当前用户外部身份，取消未完成消息并清理旧目标。 */
    @PostMapping("/binding/remove")
    @TenantIsolatedEndpoint
    public CommonResult<Void> unbind(@RequestBody ReqImBotOperationVO vo) {
        service.unbind(ImBotWebConverter.toDTO(vo).getBotKey()); return CommonResult.success();
    }
    /** 受理当前用户的幂等主动发送请求。 */
    @PostMapping("/message/send")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotMessageVO> send(@RequestBody ReqImBotSendVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.send(ImBotWebConverter.toDTO(vo))));
    }
    /** 分页查询当前身份可见的已互动目标会话。 */
    @PostMapping("/session/page")
    @TenantIsolatedEndpoint
    public CommonResultPage<RespImBotSessionVO> sessions(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotSessionDTO> page=service.pageSessions(ImBotWebConverter.toDTO(vo),false);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 分页查询当前身份可见的消息处理与发送记录。 */
    @PostMapping("/message/page")
    @TenantIsolatedEndpoint
    public CommonResultPage<RespImBotMessageVO> messages(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotMessageDTO> page=service.pageMessages(ImBotWebConverter.toDTO(vo),false);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 读取一条可访问消息的正文及处理和发送状态。 */
    @GetMapping("/message/detail")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotMessageVO> messageDetail(@RequestParam String messageId) {
        return CommonResult.success(ImBotWebConverter.toVO(service.messageDetail(WebIdConverter.toLong(messageId,"messageId"),false)));
    }
    /** 取消未处理消息或请求精确停止已受理运行。 */
    @PostMapping("/message/cancel")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotMessageVO> cancel(@RequestBody ReqImBotOperationVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.cancel(ImBotWebConverter.toDTO(vo).getMessageId(),vo.getReason(),false)));
    }
}
