package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotDocumentAction;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.web.converter.ImBotWebConverter;
import cn.com.servyou.work.web.request.imbot.ReqImBotDocumentVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 文档输入边界的纯参数验证，不替代平台真实收发验证。 */
class ImBotCloudDocumentTest {
    @Test void fixedActionCannotOverrideAddressOrInjectResourcePath() {
        Map<String,Object> args=new HashMap<>();args.put("document_id","abc_123");
        ImBotDocumentServiceImpl.validate(ImBotDocumentAction.FEISHU_DOC_READ,args);
        assertTrue(ImBotDocumentServiceImpl.url(ImBotDocumentAction.FEISHU_DOC_READ,args).endsWith("/documents/abc_123/raw_content"));
        args.put("url","https://example.com");assertThrows(BizException.class,()->ImBotDocumentServiceImpl.validate(ImBotDocumentAction.FEISHU_DOC_READ,args));
        args.remove("url");args.put("document_id","abc/../../auth");assertThrows(BizException.class,()->ImBotDocumentServiceImpl.validate(ImBotDocumentAction.FEISHU_DOC_READ,args));
        assertThrows(BizException.class,()->ImBotDocumentAction.fromCode("feishu.http.execute"));
    }
    @Test void wecomEndpointOnlyAcceptsOfficialHttpsWithoutUserInfoOrFragments() {
        ImBotWecomDocuments.requireEndpoint("https://qyapi.weixin.qq.com/mcp?key=test");
        for(String url:Arrays.asList("http://qyapi.weixin.qq.com/mcp","https://qyapi.weixin.qq.com.evil.example/mcp","https://127.0.0.1/mcp","https://user:secret@qyapi.weixin.qq.com/mcp","https://qyapi.weixin.qq.com/mcp#token"))
            assertThrows(BizException.class,()->ImBotWecomDocuments.requireEndpoint(url));
    }
    @Test void webFileIdentifiersMustRemainStringsAndFitLongRange() {
        ReqImBotDocumentVO request=new ReqImBotDocumentVO();request.setBotKey("2122239202842525698");request.setConversationFileId("9223372036854775807");
        assertEquals(Long.MAX_VALUE,ImBotWebConverter.toDTO(request).getConversationFileId().longValue());
        request.setConversationFileId("9223372036854775808");assertThrows(BizException.class,()->ImBotWebConverter.toDTO(request));
    }
    @Test void listRequestsKeepPaginationAndQueryIsEncoded() {
        Map<String,Object> args=new HashMap<>();args.put("page_size",101);
        assertThrows(BizException.class,()->ImBotDocumentServiceImpl.validate(ImBotDocumentAction.FEISHU_FILE_LIST,args));
        args.put("page_size",20);args.put("page_token","a&b=c");ImBotDocumentServiceImpl.validate(ImBotDocumentAction.FEISHU_FILE_LIST,args);
        assertTrue(ImBotDocumentServiceImpl.url(ImBotDocumentAction.FEISHU_FILE_LIST,args).contains("a%26b%3Dc"));
    }
}
