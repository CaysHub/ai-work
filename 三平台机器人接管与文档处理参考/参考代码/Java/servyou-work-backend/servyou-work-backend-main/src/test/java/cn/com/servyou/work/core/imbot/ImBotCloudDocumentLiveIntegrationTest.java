package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotDocumentAction;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.hutool.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用指定真实飞书应用核验；文档写入额外受显式开关控制，不发送聊天消息。 */
@EnabledIfSystemProperty(named="im.document.live",matches="true")
class ImBotCloudDocumentLiveIntegrationTest {
    @Test void realApplicationTokenAndDocumentPermissions()throws Exception {
        String appId=System.getenv("FEISHU_DOCUMENT_APP_ID"),secret=System.getenv("FEISHU_DOCUMENT_APP_SECRET");
        assertNotNull(appId);assertNotNull(secret);
        Map<String,String> headers=ImBotFeishuAuth.headers(appId,secret);
        assertTrue(headers.get("Authorization").startsWith("Bearer "));
        Map<String,Object> arguments=Collections.singletonMap("page_size",(Object)5);
        String action=ImBotDocumentAction.FEISHU_FILE_LIST.getCode();
        OkHttpClientUtil.HttpResult response=OkHttpClientUtil.jsonNoRetry("GET",ImBotDocumentServiceImpl.url(ImBotDocumentAction.FEISHU_FILE_LIST,arguments),headers,null);
        Integer code=JSONUtil.parseObj(response.getBody()).getInt("code");assertNotNull(code);
        System.out.println("FEISHU_CLOUD_DOCUMENT_LIVE httpStatus="+response.getStatusCode()+" providerCode="+code);
        if(code==0)assertNotNull(ImBotDocumentServiceImpl.requireFeishu(response,null,action));
        else {
            BizException failure=assertThrows(BizException.class,()->ImBotDocumentServiceImpl.requireFeishu(response,null,action));
            assertTrue(failure.getMessage().contains(String.valueOf(code)));assertFalse(failure.getMessage().contains(secret));
        }
    }
    @Test
    @EnabledIfSystemProperty(named="im.document.write",matches="true")
    void realDocumentCreateConvertInsertAndReadWhenApplicationPermits()throws Exception {
        Map<String,String> headers=ImBotFeishuAuth.headers(System.getenv("FEISHU_DOCUMENT_APP_ID"),System.getenv("FEISHU_DOCUMENT_APP_SECRET"));
        ImBotDocumentAction create=ImBotDocumentAction.FEISHU_DOC_CREATE;
        JSONObject body=JSONUtil.createObj().set("title","AI工作台机器人文档接口验证-"+System.currentTimeMillis());
        OkHttpClientUtil.HttpResult response=OkHttpClientUtil.jsonNoRetry(create.getMethod(),ImBotDocumentServiceImpl.url(create,Collections.emptyMap()),headers,body.toString());
        int code=JSONUtil.parseObj(response.getBody()).getInt("code",-1);
        System.out.println("FEISHU_CLOUD_DOCUMENT_CREATE_LIVE httpStatus="+response.getStatusCode()+" providerCode="+code);
        if(code!=0) {
            BizException failure=assertThrows(BizException.class,()->ImBotDocumentServiceImpl.requireFeishu(response,null,create.getCode()));
            assertTrue(failure.getMessage().contains(String.valueOf(code)));return;
        }
        JSONObject created=JSONUtil.parseObj(ImBotDocumentServiceImpl.requireFeishu(response,null,create.getCode()));
        String documentId=created.getJSONObject("document").getStr("document_id");assertNotNull(documentId);
        System.out.println("FEISHU_CLOUD_DOCUMENT_CREATED documentId="+documentId);
        String marker="仅包含固定验证文本的真实文档写入测试";
        ImBotDocumentAction convert=ImBotDocumentAction.FEISHU_DOC_CONVERT;
        OkHttpClientUtil.HttpResult converted=OkHttpClientUtil.jsonNoRetry(convert.getMethod(),ImBotDocumentServiceImpl.url(convert,Collections.emptyMap()),headers,
                JSONUtil.createObj().set("content_type","markdown").set("content","# 文档接口验证\n"+marker).toString());
        JSONObject blocks=JSONUtil.parseObj(ImBotDocumentServiceImpl.requireFeishu(converted,null,convert.getCode()));
        Map<String,Object> path=new HashMap<>();path.put("document_id",documentId);path.put("block_id",documentId);
        ImBotDocumentAction insert=ImBotDocumentAction.FEISHU_DOC_INSERT;
        JSONObject writing=JSONUtil.createObj().set("children_id",blocks.get("first_level_block_ids")).set("descendants",blocks.get("blocks"));
        ImBotDocumentServiceImpl.requireFeishu(OkHttpClientUtil.jsonNoRetry(insert.getMethod(),ImBotDocumentServiceImpl.url(insert,path),headers,writing.toString()),null,insert.getCode());
        ImBotDocumentAction read=ImBotDocumentAction.FEISHU_DOC_READ;
        Map<String,Object> result=ImBotDocumentServiceImpl.requireFeishu(OkHttpClientUtil.jsonNoRetry(read.getMethod(),
                ImBotDocumentServiceImpl.url(read,Collections.singletonMap("document_id",documentId)),headers,null),null,read.getCode());
        assertTrue(String.valueOf(result.get("content")).contains(marker));
        System.out.println("FEISHU_CLOUD_DOCUMENT_READ_WRITE_LIVE success documentId="+documentId);
    }

}
