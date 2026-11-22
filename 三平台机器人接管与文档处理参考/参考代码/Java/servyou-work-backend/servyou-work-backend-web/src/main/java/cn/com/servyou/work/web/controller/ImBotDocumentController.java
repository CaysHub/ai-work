package cn.com.servyou.work.web.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.interfaces.imbot.IImBotDocumentService;
import cn.com.servyou.work.api.model.CommonResult;
import cn.com.servyou.work.core.aspect.TenantIsolatedEndpoint;
import cn.com.servyou.work.web.converter.ImBotWebConverter;
import cn.com.servyou.work.web.request.imbot.ReqImBotDocumentVO;
import cn.com.servyou.work.web.response.imbot.RespImBotDocumentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 当前用户使用已授权机器人操作云文档的 HTTP 入口。 */
@RestController
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/im/bot/document")
@RequiredArgsConstructor
public class ImBotDocumentController {
    /** 统一文档适配服务，平台凭据只在后端读取。 */
    private final IImBotDocumentService documents;
    /** 查询机器人文档配置状态和各操作参数，不返回密钥及授权地址。 */
    @PostMapping("/capabilities")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotDocumentVO> capabilities(@RequestBody ReqImBotDocumentVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(documents.capabilities(ImBotWebConverter.toDTO(vo).getBotKey())));
    }
    /** 使用当前用户已授权的机器人执行一个固定文档动作。 */
    @PostMapping("/execute")
    @TenantIsolatedEndpoint
    public CommonResult<RespImBotDocumentVO> execute(@RequestBody ReqImBotDocumentVO vo) {
        return CommonResult.success(ImBotWebConverter.toVO(documents.execute(ImBotWebConverter.toDTO(vo))));
    }
}
