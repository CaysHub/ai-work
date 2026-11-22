package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.core.mcp.client.McpProtocolCodec;
import cn.com.servyou.work.core.utils.ExceptionLogUtil;
import cn.hutool.json.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启后使用真实七类授权，不发送消息、不创建待办会议或日程。 */
@EnabledIfSystemProperty(named="im.wecom.live",matches="true")
class ImBotWecomToolLiveIntegrationTest {
    /** 测试使用现有协议组件，不构造模拟平台。 */
    private final ImBotWecomDocuments client=new ImBotWecomDocuments(new OkHttpClient(),new McpProtocolCodec(new ObjectMapper()));

    @Test void existingTestMediaDownloadsActualBytesWithoutSendingMessages()throws Exception {
        String mediaId=System.getenv("WECOM_TEST_MEDIA_ID");
        org.junit.jupiter.api.Assumptions.assumeTrue(mediaId!=null,"媒体ID只复用本轮已上传的固定验证文件");
        String endpoint=JSONUtil.parseObj(System.getenv("WECOM_VERIFICATION_ENDPOINTS")).getStr("doc");
        JSONObject media=payload(client.call(endpoint,"media_download",JSONUtil.createObj().set("media_id",mediaId)));
        assertEquals(0,media.getInt("errcode"));
        assertEquals("企微HTTP技能媒体验证固定文本",new String(java.util.Base64.getDecoder().decode(media.getStr("base64_content")),StandardCharsets.UTF_8));
        try(ImBotFile file=ImBotFile.copyOf(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(media.getStr("base64_content"))),media.getStr("file_name"),media.getStr("content_type"),20L*1024*1024)) {
            assertEquals("企微HTTP媒体验证.txt",file.getOriginalFilename());assertTrue(file.getSize()>0);
        }
    }

    @Test void sevenCapabilitiesExposeToolsAndAcceptReadOnlyCalls()throws Exception {
        JSONObject endpoints=JSONUtil.parseObj(System.getenv("WECOM_VERIFICATION_ENDPOINTS"));
        assertEquals(7,endpoints.size());
        String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        Map<String,String> calls=new LinkedHashMap<>();
        calls.put("doc","doc_search");calls.put("msg","message_aibot_sessions_list");calls.put("todo","todo_list");
        calls.put("schedule","calendar_schedules_list");calls.put("meeting","meeting_list");calls.put("disk","disk_files_list");calls.put("contact","contact_users_search");
        for(Map.Entry<String,String> call:calls.entrySet()) {
            String endpoint=endpoints.getStr(call.getKey());assertNotNull(endpoint);
            try {
                List<Map<String,Object>> tools=client.tools(endpoint);assertFalse(tools.isEmpty());
                assertTrue(tools.stream().anyMatch(tool->call.getValue().equals(tool.get("name"))));
                JSONObject arguments=JSONUtil.createObj();
                if("doc".equals(call.getKey()))arguments.set("keywords",Collections.singletonList("AI工作台机器人文档接口验证")).set("limit",1);
                if("todo".equals(call.getKey()) || "disk".equals(call.getKey()))arguments.set("limit",1);
                if("contact".equals(call.getKey()))arguments.set("keywords",Collections.singletonList("测试员工甲"));
                if("schedule".equals(call.getKey()) || "meeting".equals(call.getKey()))arguments.set("begin_time",day+" 00:00:00").set("end_time",day+" 23:59:59");
                if("meeting".equals(call.getKey()))arguments.set("limit",1);
                Map<String,Object> result=client.call(endpoint,call.getValue(),arguments);
                assertEquals(0,payload(result).getInt("errcode"));
                assertFalse(JSONUtil.toJsonStr(result).contains("extra_identity_context"));
                System.out.println("WECOM_TOOL_LIVE capability="+call.getKey()+" tools="+tools.size()+" providerCode=0");
            } catch(Exception failure) { fail(ExceptionLogUtil.safeStackTrace(failure)); }
        }
    }

    @Test
    @EnabledIfSystemProperty(named="im.wecom.write",matches="true")
    void createOverwriteAndReadNewDocument()throws Exception {
        String endpoint=JSONUtil.parseObj(System.getenv("WECOM_VERIFICATION_ENDPOINTS")).getStr("doc");
        String docid=System.getenv("WECOM_DOCUMENT_TEST_ID");
        try {
            if(docid==null) {
                JSONObject created=payload(client.call(endpoint,"doc_create",JSONUtil.createObj().set("doc_type","doc")
                        .set("doc_name","AI工作台企微HTTP接口验证-"+System.currentTimeMillis()).set("content","固定验证初始内容")));
                save("企微测试文档创建结果.json",created);
                docid=find(created,"docid");assertNotNull(docid,"创建结果须返回真实docid");
                System.out.println("WECOM_DOCUMENT_CREATED docid="+docid);
            }
            String marker="仅用于验证后端HTTP封装的固定文本";
            JSONObject written=payload(client.call(endpoint,"doc_contents_overwrite",JSONUtil.createObj().set("docid",docid).set("content_type","text").set("content",marker)));
            assertEquals(0,written.getInt("errcode"));
            JSONObject submitted=payload(client.call(endpoint,"doc_contents_get_submit",JSONUtil.createObj().set("docid",docid).set("content_type","text")));
            save("企微测试文档读取任务.json",submitted);
            String task=find(submitted,"taskid");assertNotNull(task,"读取任务须返回真实taskid");
            boolean matched=false;
            for(int attempt=0;attempt<3;attempt++) {
                Thread.sleep(10000);
                JSONObject read=payload(client.call(endpoint,"doc_contents_get_query",JSONUtil.createObj().set("taskid",task)));
                save("企微测试文档读取结果.json",read);
                if(read.toString().contains(marker)){matched=true;break;}
            }
            assertTrue(matched,"实际读回内容应与固定写入内容一致");
            System.out.println("WECOM_DOCUMENT_READ_WRITE_LIVE success docid="+docid);
        } catch(Exception failure) { fail(ExceptionLogUtil.safeStackTrace(failure)); }
    }

    /** MCP正文JSON仅用于断言，不打印业务内容或授权地址。 */
    static JSONObject payload(Map<String,Object> result) {
        JSONObject value=JSONUtil.parseObj(result);
        if(value.getJSONObject("structuredContent")!=null)return value.getJSONObject("structuredContent");
        for(Object item:value.getJSONArray("content")) {
            JSONObject block=JSONUtil.parseObj(item);
            if(JSONUtil.isTypeJSONObject(block.getStr("text")))return JSONUtil.parseObj(block.getStr("text"));
        }
        throw new AssertionError("平台结果未提供JSON正文");
    }
    private static String find(Object node,String key) {
        if(node instanceof Map) {
            Object value=((Map<?,?>)node).get(key);if(value instanceof String)return (String)value;
            for(Object child:((Map<?,?>)node).values()){String found=find(child,key);if(found!=null)return found;}
        } else if(node instanceof Collection)for(Object child:(Collection<?>)node){String found=find(child,key);if(found!=null)return found;}
        return null;
    }
    private static void save(String name,JSONObject data)throws Exception {
        String directory=System.getProperty("im.wecom.report.dir");assertNotNull(directory);
        Files.write(Paths.get(directory,name),data.toStringPretty().getBytes(StandardCharsets.UTF_8));
    }
}
