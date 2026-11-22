package cn.com.servyou.work.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.interfaces.imbot.IImBotToolService;
import cn.com.servyou.work.api.model.CommonResult;
import cn.com.servyou.work.api.model.CommonResultPage;
import cn.com.servyou.work.api.model.imbot.ImBotToolDTO;
import cn.com.servyou.work.core.aspect.TenantIsolatedEndpoint;
import cn.com.servyou.work.web.converter.ImBotWebConverter;
import cn.com.servyou.work.web.request.imbot.ReqImBotToolVO;
import cn.com.servyou.work.web.response.imbot.RespImBotToolVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.stream.Collectors;

/** 七类企微授权能力的统一HTTP入口，客户端无需注册MCP或持有平台凭据。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/im/bot/tool")
@RequiredArgsConstructor
public class ImBotToolController {
    /** 统一平台工具服务，复用当前用户及机器人权限。 */
    private final IImBotToolService tools;

    /** 按能力类别、工具名和关键词分页查询平台实时参数定义。 */
    @PostMapping("/page")
    @TenantIsolatedEndpoint
    public CommonResultPage<RespImBotToolVO> page(@RequestBody ReqImBotToolVO request) {
        IPage<ImBotToolDTO> page=tools.page(ImBotWebConverter.toDTO(request));
        return CommonResultPage.success(page,page.getRecords().stream().map(ImBotWebConverter::toVO).collect(Collectors.toList()));
    }

    /** 在指定能力的当前工具目录内执行一次操作，写入结果未知时禁止自动重放。 */
    @PostMapping("/execute")
    @TenantIsolatedEndpoint
    public CommonResult<Map<String,Object>> execute(@RequestBody ReqImBotToolVO request) {
        return CommonResult.success(tools.execute(ImBotWebConverter.toDTO(request)));
    }
}
