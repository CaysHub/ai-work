package cn.com.servyou.work.web.controller.admin;

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
import cn.com.servyou.work.core.aspect.AdminQuery;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/** 企业机器人管理接口，沿用现有管理权限与目标租户。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/admin/im/bot")
@RequiredArgsConstructor
public class AdminImBotController {
    /** 统一机器人配置、授权及消息业务服务。 */
    private final IImBotService service;
    /** 分页查询当前管理范围或本人已授权的机器人。 */
    @PostMapping("/page")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResultPage<RespImBotVO> page(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotDTO> page=service.pageBots(ImBotWebConverter.toDTO(vo),true);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 读取管理范围内机器人配置及连接状态，可显式读取有权查看的企微 MCP 地址。 */
    @GetMapping("/detail")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResult<RespImBotVO> detail(@RequestParam String botKey,
            @RequestParam(defaultValue = "false") boolean includeMcpUrls, HttpServletResponse response) {
        if (includeMcpUrls) response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return CommonResult.success(ImBotWebConverter.toVO(service.detail(WebIdConverter.toLong(botKey,"botKey"), includeMcpUrls)));
    }
    /** 创建当前管理租户的机器人配置，默认停用。 */
    @PostMapping("/create")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> create(@RequestBody ReqImBotSaveVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.save(ImBotWebConverter.toDTO(vo),true)));
    }
    /** 按配置版本更新机器人及可选的新密钥。 */
    @PostMapping("/update")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> update(@RequestBody ReqImBotSaveVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.save(ImBotWebConverter.toDTO(vo),false)));
    }
    /** 按配置版本启用机器人，由后台持有节点建立长连接。 */
    @PostMapping("/enable")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> enable(@RequestBody ReqImBotOperationVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.enable(ImBotWebConverter.toDTO(vo),true)));
    }
    /** 按配置版本停用机器人并取消或停止相关业务。 */
    @PostMapping("/disable")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotVO> disable(@RequestBody ReqImBotOperationVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.enable(ImBotWebConverter.toDTO(vo),false)));
    }
    /** 分页查询机器人授权用户及绑定状态。 */
    @PostMapping("/user/page")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResultPage<RespImBotUserVO> users(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotUserDTO> page=service.pageUsers(ImBotWebConverter.toDTO(vo));
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 授予当前管理租户成员的机器人使用权，保留已有绑定。 */
    @PostMapping("/user/grant")
    @TenantIsolatedEndpoint
    public CommonResult<Void> grant(@RequestBody ReqImBotOperationVO vo) {
        service.grant(ImBotWebConverter.toDTO(vo),true); return CommonResult.success();
    }
    /** 撤销用户使用权并清理绑定、旧目标及未完成消息。 */
    @PostMapping("/user/revoke")
    @TenantIsolatedEndpoint
    public CommonResult<Void> revoke(@RequestBody ReqImBotOperationVO vo) {
        service.grant(ImBotWebConverter.toDTO(vo),false); return CommonResult.success();
    }
    /** 分页查询当前身份可见的已互动目标会话。 */
    @PostMapping("/session/page")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResultPage<RespImBotSessionVO> sessions(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotSessionDTO> page=service.pageSessions(ImBotWebConverter.toDTO(vo),true);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 分页查询当前身份可见的消息处理与发送记录。 */
    @PostMapping("/message/page")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResultPage<RespImBotMessageVO> messages(@RequestBody(required=false) ReqImBotQueryVO vo) {
        IPage<ImBotMessageDTO> page=service.pageMessages(ImBotWebConverter.toDTO(vo),true);
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }
    /** 读取一条可访问消息的正文及处理和发送状态。 */
    @GetMapping("/message/detail")
    @TenantIsolatedEndpoint
    @AdminQuery
    public CommonResult<RespImBotMessageVO> messageDetail(@RequestParam String messageId) {
        return CommonResult.success(ImBotWebConverter.toVO(service.messageDetail(WebIdConverter.toLong(messageId,"messageId"),true)));
    }
    /** 取消未处理消息或请求精确停止已受理运行。 */
    @PostMapping("/message/terminate")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotMessageVO> cancel(@RequestBody ReqImBotOperationVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(service.cancel(ImBotWebConverter.toDTO(vo).getMessageId(),vo.getReason(),true)));
    }
}
