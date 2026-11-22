package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.hutool.json.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实平台之外的配置与结果边界，不用模拟平台结果替代真实联调。 */
class ImBotToolTest {
    @Test void sevenConfigsMergePreserveAndClearWithoutLosingLegacyDocument() {
        StandardEnvironment env=new StandardEnvironment();String doc="https://qyapi.weixin.qq.com/mcp/v2/bot/doc?apikey=test-only-doc";
        String cipher=AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(env),doc);
        Map<String,String> changes=new LinkedHashMap<>();
        for(ImBotMcpCapability capability:ImBotMcpCapability.values())changes.put(capability.getCode(),"https://qyapi.weixin.qq.com/mcp/v2/bot/"+capability.getCode()+"?apikey=test-only-"+capability.getCode());
        String merged=ImBotMcpConfig.merge(cipher,null,changes,env);
        assertEquals(7,ImBotMcpConfig.read(merged,env).size());assertFalse(merged.contains("apikey"));
        assertEquals(merged,ImBotMcpConfig.merge(merged,null,null,env));
        String cleared=ImBotMcpConfig.merge(merged,"",null,env);
        assertEquals(6,ImBotMcpConfig.read(cleared,env).size());
        assertTrue(ImBotMcpConfig.read(cleared,env).containsKey(ImBotMcpCapability.TODO.getCode()));
        assertEquals(doc,ImBotMcpConfig.read(cipher,env).get(ImBotMcpCapability.DOCUMENT.getCode()));
    }
    @Test void wrongCategoryEndpointAndImplicitPathsAreRejected() {
        assertThrows(BizException.class,()->ImBotMcpConfig.requireEndpoint(ImBotMcpCapability.TODO,"https://qyapi.weixin.qq.com/mcp/v2/bot/doc?apikey=x"));
        assertThrows(BizException.class,()->ImBotMcpConfig.requireEndpoint(ImBotMcpCapability.TODO,"https://example.com/mcp/v2/bot/todo?apikey=x"));
        assertThrows(BizException.class,()->ImBotWecomDocuments.rejectLocalPaths(Collections.singletonMap("nested",Collections.singletonMap("file_path","/tmp/not-readable"))));
        Map<String,Object> definition=JSONUtil.parseObj("{\"inputSchema\":{\"properties\":{\"docid\":{}},\"required\":[\"docid\"]}}");
        assertThrows(BizException.class,()->ImBotWecomDocuments.validate(definition,Collections.emptyMap()));
        assertThrows(BizException.class,()->ImBotWecomDocuments.validate(definition,Collections.singletonMap("url","https://example.com")));
    }
    @Test void providerFailureCannotMasqueradeAsHttpSuccessAndIdentityContextIsRemoved() {
        JSONObject failure=JSONUtil.createObj().set("content",JSONUtil.createArray().set(JSONUtil.createObj().set("type","text").set("text","{\"errcode\":40001,\"errmsg\":\"sensitive text\"}")));
        BizException rejected=assertThrows(BizException.class,()->ImBotWecomDocuments.requireToolResult(failure));
        assertTrue(rejected.getMessage().contains("40001"));assertFalse(rejected.getMessage().contains("sensitive"));
        JSONObject success=JSONUtil.createObj().set("content",JSONUtil.createArray().set(JSONUtil.createObj().set("type","text").set("text","{\"errcode\":0,\"extra_identity_context\":\"private\",\"items_count\":0}")));
        String result=JSONUtil.toJsonStr(ImBotWecomDocuments.requireToolResult(success));
        assertFalse(result.contains("extra_identity_context"));assertFalse(result.contains("private"));assertTrue(result.contains("items_count"));
    }
}
