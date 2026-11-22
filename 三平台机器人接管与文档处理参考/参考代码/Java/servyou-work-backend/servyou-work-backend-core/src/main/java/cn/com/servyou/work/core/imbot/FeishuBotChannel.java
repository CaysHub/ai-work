package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.com.servyou.work.dao.entity.*;
import cn.hutool.json.*;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.*;
import com.lark.oapi.ws.Client;
import com.lark.oapi.ws.WorkbenchClientLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.apache.commons.lang3.StringUtils;
import javax.annotation.PreDestroy;
import java.lang.Thread;
import java.nio.file.*;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 官方SDK只负责长连接；发送复用项目无隐式重试的HTTP传输。 */
@Slf4j
public final class FeishuBotChannel implements ImBotChannel {
    private final ImBotEntity bot;private final String secret;private final Client client;private volatile boolean online,closed;
    private final String botOpenId;
    private FeishuBotChannel(ImBotEntity bot,String secret,Consumer<Inbound> receive,com.lark.oapi.okhttp.OkHttpClient transport) throws Exception {
        this.bot=bot;this.secret=secret;
        OkHttpClientUtil.HttpResult infoResponse=OkHttpClientUtil.get("https://open.feishu.cn/open-apis/bot/v3/info",headers());
        JSONObject info=JSONUtil.parseObj(infoResponse.getBody());
        if(!infoResponse.isSuccessful() || info.getInt("code",-1)!=0 || info.getJSONObject("bot")==null) {
            log.warn("飞书机器人身份校验失败 botKey={}, httpStatus={}, providerCode={}",bot.getId(),infoResponse.getStatusCode(),info.getInt("code"));
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"机器人身份校验失败");
        }
        botOpenId=info.getJSONObject("bot").getStr("open_id");if(botOpenId==null)throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"缺少机器人open_id");
        EventDispatcher dispatcher=EventDispatcher.newBuilder("","").onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler(){
            @Override public void handle(P2MessageReceiveV1 event) {
                if(closed)return;
                EventSender sender=event.getEvent().getSender();EventMessage m=event.getEvent().getMessage();
                if(sender==null || m==null || !Wire.USER.getCode().equals(sender.getSenderType()) || sender.getSenderId()==null)return;
                try {
                    JSONObject rich=readContent(m.getMessageType(),JSONUtil.parseObj(m.getContent()));
                    if(rich==null) {
                        log.info("飞书消息类型暂不支持 botKey={}, externalAppId={}, externalUserId={}, externalMessageId={}, messageType={}",
                                bot.getId(),bot.getExternalAppId(),sender.getSenderId().getOpenId(),m.getMessageId(),m.getMessageType());return;
                    }
                    boolean post=Wire.POST.getCode().equals(m.getMessageType());
                    JSONObject source=rich.getJSONObject("file");boolean file=source!=null;
                    String prompt=rich.getStr("text");
                    boolean group=Wire.GROUP.getCode().equals(m.getChatType());
                    String text=file?StringUtils.defaultIfBlank(source.getStr("file_name"),"上传附件"):prompt;
                    if(group) {
                        if(file && !post)return; // 独立媒体没有 @ 触发语义，富文本附件仍须 @ 本机器人。
                        MentionEvent own=null;if(m.getMentions()!=null)for(MentionEvent mention:m.getMentions()) if(mention.getId()!=null && botOpenId.equals(mention.getId().getOpenId()))own=mention;
                        if(own==null)return;
                        prompt=prompt==null?null:prompt.replace(own.getKey(),"").trim();if(!file)text=prompt;
                    }
                    Inbound input=new Inbound();input.setExternalUserId(sender.getSenderId().getOpenId());input.setChatId(m.getChatId());input.setMessageId(m.getMessageId());
                    input.setSessionType(group?SessionType.GROUP.getCode():SessionType.P2P.getCode());input.setContent(text);
                    input.setContentType(file?Content.FILE.getCode():Content.TEXT.getCode());
                    if(file)input.setContext(JSONUtil.createObj().set("file",source)
                            .set("prompt",prompt).set("fileCount",rich.getInt("fileCount",1)).toString());
                    receive.accept(input);
                } catch(RuntimeException failure) {
                    log.warn("飞书消息解析或入站失败 botKey={}, externalAppId={}, externalUserId={}, externalMessageId={}, messageType={}\n{}",
                            bot.getId(),bot.getExternalAppId(),sender.getSenderId().getOpenId(),m.getMessageId(),m.getMessageType(),safeStackTrace(failure));
                    if(failure instanceof BizException)throw failure;
                    throw new BizException(Error.INBOUND_FAILED.getCode(),"飞书消息解析或入站失败",failure);
                }
            }
        }).build();
        client=new Client.Builder(bot.getExternalAppId(),secret).eventHandler(dispatcher).httpClient(transport)
                .onReconnecting(()->{online=false;if(!closed)log.warn("飞书连接断开，官方SDK开始重连 botKey={}",bot.getId());})
                .onReconnected(()->{online=true;log.info("飞书连接恢复 botKey={}",bot.getId());}).build();
        try{client.start();client.awaitReady(10000);online=true;}catch(Exception e){close();throw e;}
    }
    /** 机器人事件与本人历史读取共用同一媒体解析，不为图片和音频新增消息表类型。 */
    static JSONObject readContent(String type,JSONObject payload) {
        if(Wire.POST.getCode().equals(type))return readPost(payload);
        if(Wire.TEXT.getCode().equals(type))return JSONUtil.createObj().set("text",payload.getStr("text",""));
        if(Wire.FILE.getCode().equals(type) || Wire.IMAGE.getCode().equals(type) || Wire.AUDIO.getCode().equals(type))
            return JSONUtil.createObj().set("text","").set("fileCount",1).set("file",payload
                    .set("resourceType",Wire.IMAGE.getCode().equals(type)?Wire.IMAGE.getCode():Wire.FILE.getCode()));
        return null;
    }
    /** 将飞书富文本的文字和单附件投影到既有入站格式，避免客户端合并发送时被忽略。 */
    static JSONObject readPost(JSONObject payload) {
        JSONObject post=payload;
        if(post.getJSONArray("content")==null && post.getJSONArray("content_v2")==null) {
            for(Object value:payload.values()) if(value instanceof JSONObject) {
                JSONObject localized=(JSONObject)value;
                if(localized.getJSONArray("content")!=null || localized.getJSONArray("content_v2")!=null){post=localized;break;}
            }
        }
        StringBuilder text=new StringBuilder();String title=post.getStr("title");
        if(StringUtils.isNotBlank(title))text.append(title).append('\n');
        Map<String,JSONObject> files=new LinkedHashMap<>();
        JSONArray attachments=post.getJSONArray("files");
        if(attachments!=null)for(int i=0;i<attachments.size();i++) {
            JSONObject file=attachments.getJSONObject(i);String key=file.getStr("file_key");
            if(StringUtils.isNotBlank(key))files.putIfAbsent(key,file);
        }
        JSONArray rows=post.getJSONArray("content");if(rows==null)rows=post.getJSONArray("content_v2");
        if(rows!=null)for(int i=0;i<rows.size();i++) {
            JSONArray row=rows.getJSONArray(i);
            for(int j=0;j<row.size();j++) {
                JSONObject element=row.getJSONObject(j);String tag=element.getStr("tag");
                if(Wire.TEXT.getCode().equals(tag) || Wire.LINK.getCode().equals(tag) || Wire.CODE_BLOCK.getCode().equals(tag))
                    text.append(StringUtils.defaultString(element.getStr("text")));
                else if(Wire.FILE.getCode().equals(tag) && StringUtils.isNotBlank(element.getStr("file_key")))
                    files.putIfAbsent(element.getStr("file_key"),element);
                else if(Wire.POST_IMAGE.getCode().equals(tag) && StringUtils.isNotBlank(element.getStr("image_key")))
                    files.putIfAbsent(element.getStr("image_key"),element.set("resourceType",Wire.IMAGE.getCode()));
            }
            text.append('\n');
        }
        return JSONUtil.createObj().set("text",text.toString().trim()).set("fileCount",files.size())
                .set("file",files.isEmpty()?null:files.values().iterator().next());
    }
    private Map<String,String> headers() throws Exception {
        return ImBotFeishuAuth.headers(bot.getExternalAppId(),secret);
    }
    @Override public boolean online(){return online && !closed;}
    @Override public CompletionStage<Receipt> send(ImBotMessageEntity m,ImBotSessionEntity target) {
        String text=Direction.INBOUND.getCode().equals(m.getDirection())?m.getReplyContent():m.getContent();
        if(Direction.INBOUND.getCode().equals(m.getDirection())) {
            JSONObject card=JSONUtil.createObj().set("config",JSONUtil.createObj().set("update_multi",true))
                    .set("elements",new JSONArray().put(JSONUtil.createObj().set("tag","markdown").set("content",text)));
            return sendPayload(m,target,Wire.INTERACTIVE.getCode(),card);
        }
        return sendPayload(m,target,Wire.TEXT.getCode(),JSONUtil.createObj().set("text",text));
    }
    private CompletionStage<Receipt> sendPayload(ImBotMessageEntity m,ImBotSessionEntity target,String type,JSONObject content) {
        CompletableFuture<Receipt> result=new CompletableFuture<>();
        try {
            if(!online()) {result.complete(new Receipt(Send.FAILED.getCode(),null,"连接不可用，未发送"));return result;}
            JSONObject body=JSONUtil.createObj().set("msg_type",type).set("content",content.toString()).set("uuid",m.getProviderUuid());
            String url;
            boolean update=Direction.INBOUND.getCode().equals(m.getDirection()) && Wire.INTERACTIVE.getCode().equals(type) && m.getProviderMessageId()!=null;
            if(update) {
                if(!m.getProviderMessageId().matches("[A-Za-z0-9_-]{1,256}"))throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"无效消息标识");
                url="https://open.feishu.cn/open-apis/im/v1/messages/"+m.getProviderMessageId();
                body=JSONUtil.createObj().set("content",content.toString());
            } else if(m.getExternalMessageId()!=null) {
                if(m.getExternalMessageId()==null || !m.getExternalMessageId().matches("[A-Za-z0-9_-]{1,256}"))throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"无效消息标识");
                url="https://open.feishu.cn/open-apis/im/v1/messages/"+m.getExternalMessageId()+"/reply";
            } else {url="https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id";body.set("receive_id",target.getExternalChatId());}
            OkHttpClientUtil.HttpResult response=update?OkHttpClientUtil.patchJsonNoRetry(url,headers(),body.toString())
                    :OkHttpClientUtil.postJsonNoRetry(url,headers(),body.toString());
            JSONObject data=JSONUtil.parseObj(response.getBody());Integer code=data.getInt("code");
            if(!response.isSuccessful() || !Integer.valueOf(0).equals(code))
                log.warn("飞书发送未成功 botKey={}, messageId={}, requestId={}, httpStatus={}, providerCode={}",bot.getId(),m.getId(),m.getRequestId(),response.getStatusCode(),code);
            if(response.isSuccessful() && Integer.valueOf(0).equals(code)) {
                JSONObject accepted=data.getJSONObject("data");
                result.complete(new Receipt(Send.ACCEPTED.getCode(),update?m.getProviderMessageId():accepted==null?null:accepted.getStr("message_id"),null));
            } else if(response.getStatusCode()==429 || Integer.valueOf(230020).equals(code) || Integer.valueOf(99991400).equals(code)) result.complete(new Receipt(Send.PENDING.getCode(),null,"平台限流，等待下次发送"));
            else if(response.getStatusCode()>=500) result.complete(new Receipt(Send.UNKNOWN.getCode(),null,"平台服务器错误，受理状态未知"));
            else result.complete(new Receipt(Send.FAILED.getCode(),null,"飞书明确拒绝：HTTP "+response.getStatusCode()+"，code="+code));
        } catch(Exception e){
            log.warn("飞书发送异常 botKey={}, messageId={}, requestId={}\n{}",bot.getId(),m.getId(),m.getRequestId(),safeStackTrace(e));
            result.complete(new Receipt(Send.UNKNOWN.getCode(),null,"网络或回执异常，发送结果未知"));
        }
        return result;
    }
    @Override public ImBotFile downloadFile(ImBotMessageEntity message,JSONObject source)throws Exception {
        return downloadResource(message,source,headers());
    }
    /** 固定官方资源地址，调用者显式提供机器人或本人令牌，禁止身份回退。 */
    static ImBotFile downloadResource(ImBotMessageEntity message,JSONObject source,Map<String,String> headers)throws Exception {
        boolean image=Wire.IMAGE.getCode().equals(source.getStr("resourceType")) || StringUtils.isNotBlank(source.getStr("image_key"));
        String key=source.getStr(image?"image_key":"file_key"),id=message.getExternalMessageId();
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,256}") || key==null || !key.matches("[A-Za-z0-9_-]{1,512}"))
            throw new BizException(Error.FILE_FAILED.getCode(),"飞书文档资源标识无效");
        return ImBotFile.download("https://open.feishu.cn/open-apis/im/v1/messages/"+id+"/resources/"+key+"?type="+(image?Wire.IMAGE.getCode():Wire.FILE.getCode()),headers,source.getStr("file_name"),null);
    }
    @Override public CompletionStage<Receipt> sendFile(ImBotMessageEntity message,ImBotSessionEntity target,Path file,String name)throws Exception {
        long size=Files.size(file);
        if(size<=0 || size>30L*1024*1024)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"飞书成果文件必须非空且不超过30MB，请在工作台下载");
        // stream 为官方通用文件类型，保留原文件名即可覆盖 Word、Excel、PDF 等文档。
        MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file_type",Wire.STREAM.getCode())
                .addFormDataPart("file_name",name).addFormDataPart("file",name,RequestBody.create(file.toFile(),MediaType.parse("application/octet-stream"))).build();
        OkHttpClientUtil.HttpResult response=OkHttpClientUtil.postFileNoRetry("https://open.feishu.cn/open-apis/im/v1/files",headers(),body);
        JSONObject result=JSONUtil.parseObj(response.getBody());JSONObject data=result.getJSONObject("data");
        if(!response.isSuccessful() || result.getInt("code",-1)!=0 || data==null || data.getStr("file_key")==null){
            log.warn("飞书文件上传失败 botKey={}, messageId={}, httpStatus={}, providerCode={}",bot.getId(),message.getId(),response.getStatusCode(),result.getInt("code"));
            throw new BizException(Error.FILE_FAILED.getCode(),"飞书文件上传失败，请检查文件资源上传权限");
        }
        return sendPayload(message,target,Wire.FILE.getCode(),JSONUtil.createObj().set("file_key",data.getStr("file_key")));
    }
    @Override public void close(){closed=true;online=false;if(client!=null)WorkbenchClientLifecycle.close(client);}
    @Component
    @ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
    public static class Factory implements ImBotChannel.Factory {
        private final com.lark.oapi.okhttp.OkHttpClient transport=new com.lark.oapi.okhttp.OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).build();
        @Override public ImBotChannel connect(ImBotEntity bot,String secret,Consumer<Inbound> receive)throws Exception{
            try{return new FeishuBotChannel(bot,secret,receive,transport);}
            catch(Exception e){
                log.warn("飞书建立连接失败 botKey={}\n{}",bot.getId(),safeStackTrace(e));
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                if(e instanceof BizException)throw e;
                throw new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"飞书建立连接失败",e);
            }
        }
        @PreDestroy public void close(){transport.dispatcher().executorService().shutdownNow();transport.connectionPool().evictAll();}
    }
}
