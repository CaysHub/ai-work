package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.com.servyou.work.dao.entity.ImBotMessageEntity;
import cn.hutool.json.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.io.ByteArrayInputStream;
import java.util.*;

/** 真人消息的固定平台适配；复用HTTP连接池和已有MCP客户端，绝不退回机器人发送。 */
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
class ImBotUserGateway {
    private final ImBotWecomDocuments wecom;
    private final Environment environment;
    /** 一个固定时间窗口的一页数据，游标完整处理后才能推进窗口。 */
    @Data static class Page {
        /** 归一化后的消息或会话元数据。 */
        private final JSONArray items;
        /** 下一页游标，末页为空。 */
        private final String next;
    }
    Page discover(ImBotEntity b,JSONObject c,long start,long end,String cursor)throws Exception {
        if(Platform.DINGTALK.getCode().equals(b.getPlatform()))return DingTalkUserMessages.discover(c,start,cursor);
        if(Platform.WECOM.getCode().equals(b.getPlatform())){
            JSONObject data=mcp(b,UserTool.GROUPS,range(start,end,cursor));
            JSONArray chats=wecomItems(data,"chats");
            JSONArray out=new JSONArray();for(int i=0;i<chats.size();i++){
                JSONObject chat=chats.getJSONObject(i);out.add(JSONUtil.createObj().set("chatId",chat.getStr("chat_id")).set("type",SessionType.GROUP.getCode()).set("name",chat.getStr("chat_name")));
            }return new Page(out,next(data,"next_cursor"));
        }
        JSONObject filter=JSONUtil.createObj().set("time_range",JSONUtil.createObj().set("start_time",Instant.ofEpochSecond(start).toString()).set("end_time",Instant.ofEpochSecond(end).toString()))
                .set("from_types",Collections.singletonList(Wire.USER.getCode()));
        HttpUrl.Builder url=HttpUrl.get("https://open.feishu.cn/open-apis/im/v1/messages/search").newBuilder().addQueryParameter("page_size","30");
        if(StringUtils.isNotBlank(cursor))url.addQueryParameter("page_token",cursor);
        JSONObject data=feishuUrl("POST",url.build().toString(),c.getStr("accessToken"),JSONUtil.createObj().set("query","").set("filter",filter)).getJSONObject("data");
        if(data==null || data.getJSONArray("items")==null)throw invalid("飞书搜索响应缺少items");
        // 搜索主动声明结果不全时停止，禁止把不完整结果当成完整水位。
        if(StringUtils.isNotBlank(data.getStr("notice")))throw invalid("飞书搜索返回补充限制，请核对平台搜索覆盖范围");
        JSONArray out=new JSONArray();for(Object item:data.getJSONArray("items")){
            JSONObject meta=JSONUtil.parseObj(item).getJSONObject("meta_data");if(meta==null)throw invalid("飞书搜索缺少meta_data");
            out.add(JSONUtil.createObj().set("chatId",meta.getStr("chat_id")).set("type",meta.getBool("is_p2p_chat",false)?SessionType.P2P.getCode():SessionType.GROUP.getCode()));
        }return new Page(out,next(data,"page_token"));
    }
    Page history(ImBotEntity b,JSONObject c,String chat,long start,long end,String cursor)throws Exception {
        if(Platform.DINGTALK.getCode().equals(b.getPlatform()))return DingTalkUserMessages.history(c,chat,start,end,cursor);
        JSONObject data;JSONArray raw;JSONArray out=new JSONArray();
        if(Platform.WECOM.getCode().equals(b.getPlatform())){
            JSONObject args=range(start,end,cursor);args.set("chat_id",chat);data=mcp(b,UserTool.MESSAGES,args);raw=wecomItems(data,"messages");
            for(Object value:raw){JSONObject m=JSONUtil.parseObj(value);String sender=m.getStr("userid");
                if(StringUtils.isBlank(sender))continue;
                long time=LocalDateTime.parse(m.getStr("send_time"),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
                JSONObject text=m.getJSONObject("text");
                String type=m.getStr("msg_type");
                JSONObject item=JSONUtil.createObj().set("sender",sender).set("time",time).set("type",type)
                        .set("text",text==null?"":text.getStr("content","")).set("senderType",Wire.USER.getCode());
                if (m.get("mentioned_list") instanceof JSONArray)
                    item.set("mentionedUserIds",m.getJSONArray("mentioned_list"));
                if(Wire.FILE.getCode().equals(type) || Wire.IMAGE.getCode().equals(type) || Wire.VOICE.getCode().equals(type) || Wire.AUDIO.getCode().equals(type)) {
                    JSONObject media=m.getJSONObject(type);
                    if(media!=null && StringUtils.isNotBlank(media.getStr("media_id")))item.set("file",JSONUtil.createObj()
                            .set("media_id",media.getStr("media_id")).set("file_name",media.getStr("file_name"))).set("fileCount",1);
                }
                out.add(item);
            }return new Page(out,next(data,"next_cursor"));
        }
        HttpUrl.Builder url=HttpUrl.get("https://open.feishu.cn/open-apis/im/v1/messages").newBuilder()
                .addQueryParameter("container_id_type","chat").addQueryParameter("container_id",chat)
                .addQueryParameter("start_time",String.valueOf(start)).addQueryParameter("end_time",String.valueOf(end))
                .addQueryParameter("sort_type","ByCreateTimeAsc").addQueryParameter("page_size","50");
        if(StringUtils.isNotBlank(cursor))url.addQueryParameter("page_token",cursor);
        data=feishuUrl("GET",url.build().toString(),c.getStr("accessToken"),null).getJSONObject("data");
        if(data==null || (raw=data.getJSONArray("items"))==null)throw invalid("飞书消息响应缺少items");
        for(Object value:raw){JSONObject m=JSONUtil.parseObj(value),sender=m.getJSONObject("sender");
            if(sender==null || m.getBool("deleted",false))continue;
            JSONObject body=m.getJSONObject("body");
            JSONObject content=body==null?null:FeishuBotChannel.readContent(m.getStr("msg_type"),JSONUtil.parseObj(body.getStr("content","{}")));
            JSONObject item=JSONUtil.createObj().set("id",m.getStr("message_id")).set("sender",sender.getStr("id"))
                    .set("time",m.getLong("create_time")).set("position",m.getLong("message_position",0L))
                    .set("type",m.getStr("msg_type")).set("text",content==null?"":content.getStr("text","")).set("senderType",sender.getStr("sender_type"));
            if (m.get("mentions") instanceof JSONArray)
                item.set("mentionedUserIds",ImBotGroupMentions.feishuIds(m.getJSONArray("mentions")));
            if(content!=null && content.getJSONObject("file")!=null)item.set("file",content.getJSONObject("file")).set("fileCount",content.getInt("fileCount",1));
            out.add(item);
        }return new Page(out,next(data,"page_token"));
    }
    /** 使用当前本人授权下载附件；企微解析Base64，飞书仅尝试本人令牌，平台拒绝时不借用机器人权限。 */
    ImBotFile download(ImBotEntity b,JSONObject c,ImBotMessageEntity message,JSONObject source)throws Exception {
        if(Platform.DINGTALK.getCode().equals(b.getPlatform())) {
            throw new BizException(Error.FILE_FAILED.getCode(),"钉钉真人接管当前支持文本");
        }
        if(Platform.FEISHU.getCode().equals(b.getPlatform())) {
            String token=c.getStr("accessToken");
            if(StringUtils.isBlank(token))throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书附件读取需要本人OAuth授权");
            return FeishuBotChannel.downloadResource(message,source,Collections.singletonMap("Authorization","Bearer "+token));
        }
        String media=source.getStr("media_id");
        if(StringUtils.isBlank(media))throw invalid("企微附件缺少media_id");
        JSONObject result=mcp(b,UserTool.FILES,JSONUtil.createObj().set("media_id",media)),item=result.getJSONObject("media_item");
        JSONObject file=item==null?null:item.getJSONObject("file_path");
        String content=file==null?null:file.getStr("content");
        if(StringUtils.isBlank(content))throw invalid("企微附件响应缺少文件内容");
        if(content.length()>4*((ImBotFile.INPUT_LIMIT+2)/3))throw new BizException(Error.FILE_TOO_LARGE.getCode(),"企微附件超过100MB");
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(content);}catch(IllegalArgumentException e){throw new BizException(Error.FILE_FAILED.getCode(),"企微附件Base64内容无效",e);}
        String name=StringUtils.defaultIfBlank(file.getStr("file_name"),StringUtils.defaultIfBlank(item.getStr("file_name"),source.getStr("file_name")));
        return ImBotFile.copyOf(new ByteArrayInputStream(bytes),name,"application/octet-stream",ImBotFile.INPUT_LIMIT);
    }
    ImBotChannel.Receipt send(ImBotEntity b,JSONObject c,String chat,String text,String uuid)throws Exception {
        if(StringUtils.isBlank(text))throw invalid("真人回复正文为空");
        if(Platform.DINGTALK.getCode().equals(b.getPlatform()))return DingTalkUserMessages.send(c,chat,text,uuid);
        if(Platform.WECOM.getCode().equals(b.getPlatform())){
            if(text.length()>2048)throw invalid("企微真人消息超过2048字符，已停止发送，请在工作台精简回复");
            JSONObject args=JSONUtil.createObj().set("chat_id",chat).set("msg_type",Wire.TEXT.getCode()).set("text",JSONUtil.createObj().set("content",text));
            mcp(b,UserTool.SEND,args);return new ImBotChannel.Receipt(Send.ACCEPTED.getCode(),null,null);
        }
        JSONObject request=JSONUtil.createObj().set("receive_id",chat).set("msg_type",Wire.TEXT.getCode())
                .set("content",JSONUtil.createObj().set("text",text).toString()).set("uuid",uuid);
        JSONObject data=feishuUrl("POST","https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id",c.getStr("accessToken"),request).getJSONObject("data");
        if(data==null || StringUtils.isBlank(data.getStr("message_id")))return new ImBotChannel.Receipt(Send.UNKNOWN.getCode(),null,"飞书未返回消息ID，不自动重发");
        return new ImBotChannel.Receipt(Send.ACCEPTED.getCode(),data.getStr("message_id"),null);
    }
    private JSONObject range(long start,long end,String cursor){
        DateTimeFormatter f=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
        JSONObject args=JSONUtil.createObj().set("begin_time",f.format(Instant.ofEpochSecond(start))).set("end_time",f.format(Instant.ofEpochSecond(end)));
        if(StringUtils.isNotBlank(cursor))args.set("cursor",cursor);return args;
    }
    /** 企微成功空结果会省略数组；仅在数量明确为零且没有后续页时按空页处理。 */
    static JSONArray wecomItems(JSONObject data,String field){
        JSONArray items=data.getJSONArray(field);if(items!=null)return items;
        if(data.getInt("errcode",-1)==0 && data.getInt(field+"_count",-1)==0 && Boolean.FALSE.equals(data.getBool("has_more")))return new JSONArray();
        log.warn("企微真人读取响应缺少列表 field={}, providerCode={}, count={}, hasMore={}",field,data.getInt("errcode"),data.getInt(field+"_count"),data.getBool("has_more"));
        throw invalid("企微响应缺少"+field+"，且未确认成功空页");
    }
    private JSONObject mcp(ImBotEntity b,UserTool tool,JSONObject args)throws Exception {
        String endpoint=ImBotMcpConfig.read(b.getDocumentConfigCipher(),environment).get(ImBotMcpCapability.MESSAGE.getCode());
        if(StringUtils.isBlank(endpoint))throw new BizException(Error.TOOL_NOT_CONFIGURED.getCode(),"缺少企微消息MCP本人授权");
        return payload(wecom.call(endpoint,tool.getCode(),args));
    }
    static JSONObject payload(Map<String,Object> result){
        JSONObject r=JSONUtil.parseObj(result);JSONObject structured=r.getJSONObject("structuredContent");if(structured!=null)return structured;
        JSONArray content=r.getJSONArray("content");if(content!=null)for(Object item:content){
            JSONObject block=JSONUtil.parseObj(item);String text=block.getStr("text");
            if(JSONUtil.isTypeJSONObject(text))return JSONUtil.parseObj(text);
        }throw invalid("企微工具响应缺少结构化结果");
    }
    static JSONObject feishu(String method,String path,String token,JSONObject body)throws Exception {
        return feishuUrl(method,"https://open.feishu.cn/open-apis"+path,token,body);
    }
    private static JSONObject feishuUrl(String method,String url,String token,JSONObject body)throws Exception {
        if(StringUtils.isBlank(token))throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书接管需要本人OAuth授权");
        OkHttpClientUtil.HttpResult result=OkHttpClientUtil.jsonNoRetry(method,url,Collections.singletonMap("Authorization","Bearer "+token),body==null?null:body.toString());
        JSONObject response=JSONUtil.parseObj(result.getBody());Integer code=response.getInt("code");
        if(!result.isSuccessful() || code==null || code!=0){
            log.warn("飞书真人接口失败 method={}, path={}, httpStatus={}, providerCode={}",method,HttpUrl.get(url).encodedPath(),result.getStatusCode(),code);
            throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"飞书用户接口失败，平台code="+code+"；请核对当前用户令牌权限和授权有效期");
        }
        return response;
    }
    private static String next(JSONObject data,String field){
        if(!data.getBool("has_more",false))return null;String cursor=data.getStr(field);
        if(StringUtils.isBlank(cursor))throw invalid("平台分页仍有下一页但未返回游标");return cursor;
    }
    private static BizException invalid(String message){return new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),message);}
}
