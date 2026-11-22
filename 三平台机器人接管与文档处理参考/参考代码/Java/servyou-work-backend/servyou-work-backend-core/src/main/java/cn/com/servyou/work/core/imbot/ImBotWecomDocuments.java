package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotDocumentAction;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.mcp.McpProtocolVersions;
import cn.com.servyou.work.core.mcp.client.*;
import cn.hutool.json.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 企微七类能力共用的短时HTTP MCP适配，复用协议协商和SSE解析，不注册监听器。 */
@lombok.extern.slf4j.Slf4j
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class ImBotWecomDocuments {
    /** 复用后端已经配置的共享 HTTP 连接池。 */
    private final OkHttpClient httpClient;
    /** 复用已有 MCP 协议编解码和响应校验。 */
    private final McpProtocolCodec codec;

    /** 只接受企微官方 HTTPS 授权地址，禁止凭据被转发到任意服务。 */
    static void requireEndpoint(String url) {
        HttpUrl parsed=url==null?null:HttpUrl.parse(url);
        if(parsed==null || url.length()>8192 || !"https".equals(parsed.scheme()) || parsed.port()!=443
                || !parsed.username().isEmpty() || !parsed.password().isEmpty() || parsed.fragment()!=null
                || !("qyapi.weixin.qq.com".equals(parsed.host()) || "doc.weixin.qq.com".equals(parsed.host())
                || parsed.host().endsWith(".work.weixin.qq.com")))
            throw BizException.paramError("请配置企微官方 HTTPS 文档 Streamable HTTP 授权地址");
    }

    /** 保留旧文档工具调用入口，实际仍须出现在授权目录中。 */
    Map<String,Object> execute(String endpoint,ImBotDocumentAction action,Map<String,Object> arguments) throws Exception {
        return call(endpoint,action.getPath(),arguments);
    }

    /** 查询当前授权入口实际工具，保留完整平台参数定义，不复制一套静态协议。 */
    List<Map<String,Object>> tools(String endpoint) throws Exception {
        return use(endpoint,session->catalog(session));
    }

    /** 执行前在同一短时会话核对工具与参数；所有写入均只提交一次。 */
    Map<String,Object> call(String endpoint,String toolName,Map<String,Object> arguments) throws Exception {
        if(toolName==null || !toolName.matches("[A-Za-z0-9_-]{1,128}"))throw BizException.paramError("工具名称格式错误");
        return use(endpoint,session->{
            Map<String,Object> tool=null;
            for(Map<String,Object> candidate:catalog(session))if(toolName.equals(candidate.get("name"))){tool=candidate;break;}
            if(tool==null)throw new BizException(Error.TOOL_FAILED.getCode(),"当前授权没有提供此工具，请重新查询工具目录");
            validate(tool,arguments);
            JSONObject call=JSONUtil.createObj().set("name",toolName).set("arguments",arguments);
            McpLegacySessionAdapter.Exchange called=session.request(100,"tools/call",codec.parsePayload(call.toString().getBytes(StandardCharsets.UTF_8)),"TOOLS_CALL");
            return requireToolResult(JSONUtil.parseObj(codec.requireResult(codec.parsePayload(called.getBody()),100).toString()));
        });
    }

    /** 只做顶层参数与必填项校验，复杂类型和条件约束交给平台，避免另造Schema引擎。 */
    static void validate(Map<String,Object> tool,Map<String,Object> arguments) {
        JSONObject schema=JSONUtil.parseObj(tool.get("inputSchema"));
        JSONObject properties=schema.getJSONObject("properties");
        if(properties!=null && !properties.keySet().containsAll(arguments.keySet()))throw BizException.paramError("工具包含未声明的参数");
        JSONArray required=schema.getJSONArray("required");
        if(required!=null)for(Object name:required)if(arguments.get(String.valueOf(name))==null)throw BizException.paramError("工具缺少必填参数："+name);
        rejectLocalPaths(arguments);
    }

    /** HTTP工具不读取本机或平台本地路径，文件统一通过媒体ID及会话文件传递。 */
    static void rejectLocalPaths(Object value) {
        if(value instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet()) {
            if("file_path".equals(entry.getKey()) || "content_path".equals(entry.getKey()))throw BizException.paramError("HTTP工具不接收本地文件路径，请使用会话文件上传获得media_id");
            rejectLocalPaths(entry.getValue());
        }
        else if(value instanceof Collection)for(Object item:(Collection<?>)value)rejectLocalPaths(item);
    }

    /** HTTP成功不代表业务成功；识别正文中的errcode并移除平台内部身份上下文。 */
    static Map<String,Object> requireToolResult(JSONObject result) {
        if(Boolean.TRUE.equals(result.getBool("isError")))throw new BizException(Error.TOOL_FAILED.getCode(),"企微工具执行失败，请核对权限和参数");
        JSONArray content=result.getJSONArray("content");
        if(content!=null)for(int i=0;i<content.size();i++) {
            JSONObject block=content.getJSONObject(i);
            if("text".equals(block.getStr("type")) && JSONUtil.isTypeJSONObject(block.getStr("text"))) {
                JSONObject payload=JSONUtil.parseObj(block.getStr("text"));requireBusinessSuccess(payload);sanitize(payload);
                block.set("text",payload.toString());
            }
        }
        JSONObject structured=result.getJSONObject("structuredContent");
        if(structured!=null)requireBusinessSuccess(structured);
        sanitize(result);return result;
    }
    private static void requireBusinessSuccess(Map<?,?> payload) {
        Object code=payload.get("errcode");
        if(code!=null && !"0".equals(String.valueOf(code))) {
            String safe=String.valueOf(code).matches("-?[0-9]{1,12}")?String.valueOf(code):"UNKNOWN";
            log.warn("企微平台工具业务拒绝 providerCode={}",safe);
            throw new BizException(Error.TOOL_FAILED.getCode(),"企微工具业务失败，平台errcode="+safe+"；请核对该能力授权和参数");
        }
    }
    private static void sanitize(Object value) {
        if(value instanceof Map) {
            ((Map<?,?>)value).remove("extra_identity_context");
            for(Object nested:((Map<?,?>)value).values())sanitize(nested);
        } else if(value instanceof Collection)for(Object nested:(Collection<?>)value)sanitize(nested);
    }

    private List<Map<String,Object>> catalog(McpLegacySessionAdapter.Session session)throws Exception {
        List<Map<String,Object>> tools=new ArrayList<>();String cursor=null;Set<String> cursors=new HashSet<>();
        for(int page=0;page<10;page++) {
            JSONObject params=JSONUtil.createObj();if(cursor!=null)params.set("cursor",cursor);
            int id=2+page;
            McpLegacySessionAdapter.Exchange listed=session.request(id,"tools/list",codec.parsePayload(params.toString().getBytes(StandardCharsets.UTF_8)),"TOOLS_LIST");
            JSONObject result=JSONUtil.parseObj(codec.requireResult(codec.parsePayload(listed.getBody()),id).toString());
            JSONArray items=result.getJSONArray("tools");
            if(items!=null)for(int i=0;i<items.size();i++)tools.add(items.getJSONObject(i));
            cursor=result.getStr("nextCursor");if(cursor==null || cursor.isEmpty())return tools;
            if(!cursors.add(cursor))break;
        }
        throw new BizException(Error.TOOL_FAILED.getCode(),"平台工具目录分页未结束，请稍后查询");
    }

    private <T> T use(String endpoint,SessionWork<T> work) throws Exception {
        requireEndpoint(endpoint);
        OkHttpClient client=McpHttpTransportSupport.secureBuilder(httpClient).dns(new cn.com.servyou.work.core.mcp.runtime.McpSafeDns()).callTimeout(45,TimeUnit.SECONDS)
                .readTimeout(30,TimeUnit.SECONDS).build();
        McpLegacySessionAdapter adapter=new McpLegacySessionAdapter(codec);
        McpLegacySessionAdapter.Session session=adapter.open(adapter.validationPlan(McpProtocolVersions.AUTO,
                McpProtocolVersions.legacy()),"servyou-work-document","1",(payload,version,id,required,stage)->{
            Request.Builder request=new Request.Builder().url(endpoint)
                    .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",version)
                    .post(RequestBody.create(payload,MediaType.get("application/json")));
            if(id!=null)request.header("Mcp-Session-Id",id);
            try(Response response=client.newCall(request.build()).execute()) {
                if(response.code()==401 || response.code()==403)
                    throw new BizException(Error.TOOL_PERMISSION_DENIED.getCode(),"企微能力授权无效或已过期，请重新授权并更新配置");
                if(!response.isSuccessful())throw new BizException(Error.TOOL_FAILED.getCode(),"企微能力服务请求失败，HTTP "+response.code());
                byte[] bytes=new byte[0];
                if(required) {
                    if(response.body()==null)throw new IOException("MCP response missing");
                    bytes=codec.readHttpPayload(response.body().byteStream(),response.header("Content-Type"),payload,8*1024*1024);
                }
                return new McpLegacySessionAdapter.Exchange(bytes,response.header("Mcp-Session-Id"));
            }
        });
        // 单次业务请求内完成协商、发现与调用；调用结束关闭服务端 session，不保留后台轮询。
        try {
            return work.run(session);
        } finally {
            if(session.getSessionId()!=null) {
                Request cleanup=new Request.Builder().url(endpoint).header("Mcp-Session-Id",session.getSessionId())
                        .header("MCP-Protocol-Version",session.getProtocolVersion()).delete().build();
                try(Response ignored=client.newCall(cleanup).execute()) { /* 不支持关闭的服务由平台回收。 */ }
                catch(IOException failure) { log.warn("企微能力MCP会话清理失败\n{}",cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace(failure)); }
            }
        }
    }
    /** 复用单次业务请求内的已协商会话，不建立常驻任务。 */
    @FunctionalInterface private interface SessionWork<T> {
        /** 在短时会话内执行目录查询或一次工具调用。 */
        T run(McpLegacySessionAdapter.Session session)throws Exception;
    }
}
