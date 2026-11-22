package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.dao.entity.*;
import cn.hutool.json.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.*;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.*;
import java.io.InputStream;
import cn.hutool.crypto.digest.DigestUtil;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 所有企微Bot共用一个Netty I/O线程；I/O回调只处理协议和投递，不做JDBC。 */
@Slf4j
public final class WecomBotChannel extends SimpleChannelInboundHandler<Object> implements ImBotChannel {
    private static final URI URI_WS=URI.create("wss://openws.work.weixin.qq.com/");
    private final ImBotEntity bot;private final String secret;private final Consumer<Inbound> receive;
    private final String connectionId=UUID.randomUUID().toString();private final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final CompletableFuture<Void> ready=new CompletableFuture<>();private volatile Channel channel;private volatile boolean subscribed,closed;
    private ScheduledFuture<?> heartbeat;
    private WecomBotChannel(ImBotEntity b,String secret,Consumer<Inbound> receive){this.bot=b;this.secret=secret;this.receive=receive;}
    @Override public void channelActive(ChannelHandlerContext ctx)throws Exception {channel=ctx.channel();super.channelActive(ctx);}
    @Override public void channelRead0(ChannelHandlerContext ctx,Object frame) {
        if(frame instanceof FullHttpResponse)return;
        if(frame instanceof CloseWebSocketFrame){close();return;}
        if(frame instanceof PingWebSocketFrame){ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame)frame).content().retain()));return;}
        if(!(frame instanceof TextWebSocketFrame))return;
        JSONObject json=JSONUtil.parseObj(((TextWebSocketFrame)frame).text());JSONObject headers=json.getJSONObject("headers");
        String reqId=headers==null?null:headers.getStr("req_id");String cmd=json.getStr("cmd");
        if(reqId!=null && json.containsKey("errcode")) {CompletableFuture<JSONObject> response=pending.remove(reqId);if(response!=null)response.complete(json);return;}
        if(!subscribed || !WecomCommand.MESSAGE.getCode().equals(cmd))return;
        JSONObject body=json.getJSONObject("body");if(body==null)return;
        Inbound input=readMessage(body,connectionId,reqId);if(input!=null)receive.accept(input);
    }
    /** 接收官方文本、语音转写、图片、文件与图文混排；纯媒体继续限定单聊。 */
    static Inbound readMessage(JSONObject body,String connectionId,String reqId) {
        String type=body.getStr("msgtype");JSONObject from=body.getJSONObject("from");if(from==null)return null;
        boolean group=Wire.GROUP.getCode().equals(body.getStr("chattype")),mixed=Wire.MIXED.getCode().equals(type);
        JSONObject source=null;String text=null;int count=0;
        if(Wire.TEXT.getCode().equals(type) || Wire.VOICE.getCode().equals(type)) {
            JSONObject payload=body.getJSONObject(type);if(payload==null || group && Wire.VOICE.getCode().equals(type))return null;
            // 官方语音回调直接提供识别文本，避免重新下载、重复转写。
            text=payload.getStr("content");
        } else if(Wire.FILE.getCode().equals(type) || Wire.IMAGE.getCode().equals(type)) {
            if(group)return null;source=body.getJSONObject(type);if(source==null)return null;count=1;
        } else if(mixed) {
            JSONObject payload=body.getJSONObject(type);JSONArray items=payload==null?null:payload.getJSONArray("msg_item");if(items==null)return null;
            StringBuilder prompt=new StringBuilder();
            for(int i=0;i<items.size();i++) {
                JSONObject item=items.getJSONObject(i);
                if(Wire.TEXT.getCode().equals(item.getStr("msgtype")) && item.getJSONObject("text")!=null)
                    prompt.append(item.getJSONObject("text").getStr("content","")).append('\n');
                else if(Wire.IMAGE.getCode().equals(item.getStr("msgtype")) && item.getJSONObject("image")!=null){count++;if(source==null)source=item.getJSONObject("image");}
            }
            text=prompt.toString().trim();
        } else return null;
        Inbound input=new Inbound();input.setExternalUserId(from.getStr("userid"));input.setChatId(group?body.getStr("chatid"):from.getStr("userid"));
        input.setMessageId(body.getStr("msgid"));input.setSessionType(group?SessionType.GROUP.getCode():SessionType.P2P.getCode());
        input.setContentType(source==null?Content.TEXT.getCode():Content.FILE.getCode());
        input.setContent(source==null?text:org.apache.commons.lang3.StringUtils.defaultIfBlank(source.getStr("file_name"),"上传附件"));
        JSONObject context=JSONUtil.createObj().set("connection",connectionId).set("reqId",reqId);
        if(source!=null)context.set("file",source).set("prompt",text).set("fileCount",count);
        input.setContext(context.toString());return input;
    }
    @Override public void userEventTriggered(ChannelHandlerContext ctx,Object event) throws Exception {
        if(event instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent && event==WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            request(WecomCommand.SUBSCRIBE,UUID.randomUUID().toString(),JSONUtil.createObj().set("bot_id",bot.getExternalAppId()).set("secret",secret))
                    .whenComplete((r,e)->{
                        if(e!=null || r.getInt("errcode",-1)!=0){
                            log.warn("企微订阅失败 botKey={}, connectionId={}, providerCode={}\n{}",bot.getId(),connectionId,r==null?null:r.getInt("errcode"),safeStackTrace(e));
                            ready.completeExceptionally(new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"企微订阅失败",e));close();return;
                        }
                        subscribed=true;ready.complete(null);
                        heartbeat=ctx.executor().scheduleAtFixedRate(()->request(WecomCommand.PING,UUID.randomUUID().toString(),null).whenComplete((p,t)->{
                            if(t!=null || p.getInt("errcode",-1)!=0){
                                if(!closed)log.warn("企微心跳失败 botKey={}, connectionId={}, providerCode={}\n{}",bot.getId(),connectionId,p==null?null:p.getInt("errcode"),safeStackTrace(t));
                                close();
                            }
                        }),30,30,TimeUnit.SECONDS);
                    });
        }
        super.userEventTriggered(ctx,event);
    }
    private CompletionStage<JSONObject> request(WecomCommand cmd,String reqId,JSONObject body) {
        CompletableFuture<JSONObject> result=new CompletableFuture<>();Channel c=channel;
        if(closed || c==null || !c.isActive()){result.completeExceptionally(new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"连接已关闭"));return result;}
        if(pending.putIfAbsent(reqId,result)!=null){result.completeExceptionally(new BizException(Error.IDEMPOTENCY_CONFLICT.getCode(),"请求已在途"));return result;}
        JSONObject frame=JSONUtil.createObj().set("cmd",cmd.getCode()).set("headers",JSONUtil.createObj().set("req_id",reqId));if(body!=null)frame.set("body",body);
        c.writeAndFlush(new TextWebSocketFrame(frame.toString())).addListener(f->{if(!f.isSuccess()){pending.remove(reqId);result.completeExceptionally(new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"网络写入失败",f.cause()));}});
        ScheduledFuture<?> timeout=c.eventLoop().schedule(()->{if(pending.remove(reqId,result))result.completeExceptionally(new BizException(Error.PLATFORM_REQUEST_TIMEOUT.getCode(),"平台回执超时"));},8,TimeUnit.SECONDS);
        result.whenComplete((r,e)->timeout.cancel(false));return result;
    }
    @Override public CompletionStage<Receipt> send(ImBotMessageEntity m,ImBotSessionEntity target) {
        String content=Direction.INBOUND.getCode().equals(m.getDirection())?m.getReplyContent():m.getContent();
        JSONObject body;WecomCommand cmd;String reqId;
        JSONObject context=JSONUtil.parseObj(org.apache.commons.lang3.StringUtils.defaultIfBlank(m.getReplyContext(),"{}"));
        boolean inbound=Direction.INBOUND.getCode().equals(m.getDirection());
        boolean waiting=cn.com.servyou.work.api.enums.ImBotEnums.Process.WAITING_INPUT.getCode().equals(m.getProcessState());
        if(inbound && !connectionId.equals(context.getStr("connection")) && !ImBotExecution.terminal(m.getProcessState()) && !waiting)
            return CompletableFuture.completedFuture(new Receipt(Send.PENDING.getCode(),null,"连接已重建，待本轮问题或终态再发送"));
        if(inbound && connectionId.equals(context.getStr("connection")) && !context.getBool("streamFallback",false)) {
            cmd=WecomCommand.REPLY;reqId=context.getStr("reqId");
            body=JSONUtil.createObj().set("msgtype",Wire.STREAM.getCode()).set("stream",JSONUtil.createObj().set("id",m.getProviderUuid()).set("finish",context.getBool("streamClosed",ImBotExecution.terminal(m.getProcessState()) || waiting)).set("content",content));
        } else {
            cmd=WecomCommand.SEND;reqId=Direction.INBOUND.getCode().equals(m.getDirection())?UUID.randomUUID().toString():m.getProviderUuid();
            // 主动文本使用 markdown；TEXT 先转义 Markdown 标点，维持纯文本显示。
            if(Direction.OUTBOUND.getCode().equals(m.getDirection()) && Content.TEXT.getCode().equals(m.getContentType()))content=ImBotContent.wecomText(content);
            body=JSONUtil.createObj().set("chatid",target.getExternalChatId()).set("chat_type",SessionType.GROUP.getCode().equals(target.getSessionType())?SessionType.GROUP.getWecomCode():SessionType.P2P.getWecomCode())
                    .set("msgtype",Wire.MARKDOWN.getCode()).set("markdown",JSONUtil.createObj().set("content",content));
        }
        return request(cmd,reqId,body).handle((response,error)->{
            if(error!=null){
                log.warn("企微发送回执异常 botKey={}, connectionId={}, messageId={}, requestId={}\n{}",bot.getId(),connectionId,m.getId(),m.getRequestId(),safeStackTrace(error));
                return new Receipt(Send.UNKNOWN.getCode(),null,"发送回执不明，不自动重发");
            }
            int code=response.getInt("errcode",-1);
            if(code!=0)log.warn("企微拒绝消息 botKey={}, connectionId={}, messageId={}, requestId={}, providerCode={}",bot.getId(),connectionId,m.getId(),m.getRequestId(),code);
            return new Receipt(code==0?Send.ACCEPTED.getCode():Send.FAILED.getCode(),null,code==0?null:"企微明确拒绝：code="+code);
        });
    }
    @Override public boolean online(){return subscribed && !closed && channel!=null && channel.isActive();}
    @Override public ImBotFile downloadFile(ImBotMessageEntity message,JSONObject source)throws Exception {
        String url=source.getStr("url"),key=source.getStr("aeskey");
        URI uri=URI.create(url);String host=uri.getHost();
        // 下载地址来自签名回调；仍限定官方资源域名和 HTTPS，禁止转向内部地址。
        if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null || !host.startsWith("ww-aibot-")
                || !host.endsWith(".myqcloud.com") || uri.getUserInfo()!=null || uri.getPort()!=-1 && uri.getPort()!=443 || key==null)
            throw new BizException(Error.FILE_FAILED.getCode(),"企微文档下载地址或密钥无效");
        return ImBotFile.download(url,Collections.emptyMap(),source.getStr("file_name"),key);
    }
    @Override public CompletionStage<Receipt> sendFile(ImBotMessageEntity message,ImBotSessionEntity target,Path file,String name)throws Exception {
        JSONObject context=JSONUtil.parseObj(org.apache.commons.lang3.StringUtils.defaultIfBlank(message.getReplyContext(),"{}"));
        boolean reply=connectionId.equals(context.getStr("connection"));
        String mediaId=uploadFile(file,name);
        JSONObject body=JSONUtil.createObj().set("msgtype",Wire.FILE.getCode()).set("file",JSONUtil.createObj().set("media_id",mediaId));
        if(!reply)body.set("chatid",target.getExternalChatId()).set("chat_type",SessionType.GROUP.getCode().equals(target.getSessionType())?SessionType.GROUP.getWecomCode():SessionType.P2P.getWecomCode());
        return request(reply?WecomCommand.REPLY:WecomCommand.SEND,reply?context.getStr("reqId"):message.getProviderUuid(),body).handle((response,error)->{
            if(error!=null){log.warn("企微文件发送回执异常 botKey={}, messageId={}\n{}",bot.getId(),message.getId(),safeStackTrace(error));return new Receipt(Send.UNKNOWN.getCode(),null,"文件发送结果未知，不自动重发");}
            int code=response.getInt("errcode",-1);
            if(code!=0)log.warn("企微拒绝文件消息 botKey={}, messageId={}, providerCode={}",bot.getId(),message.getId(),code);
            return new Receipt(code==0?Send.ACCEPTED.getCode():Send.FAILED.getCode(),null,code==0?null:"企微拒绝文件消息：code="+code);
        });
    }
    /** 分片上传在工作线程执行；连接中断时停止，重试尚未发送的素材不会重复发送业务消息。 */
    String uploadFile(Path file,String name)throws Exception {
        long size=Files.size(file);int chunkSize=256*1024;
        if(size<5 || size>20L*1024*1024)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"企微成果文件必须为5字节至20MB，请在工作台下载");
        JSONObject init=checked(request(WecomCommand.UPLOAD_INIT,UUID.randomUUID().toString(),JSONUtil.createObj()
                .set("type",Wire.FILE.getCode()).set("filename",name).set("total_size",size)
                .set("total_chunks",(size+chunkSize-1)/chunkSize).set("md5",DigestUtil.md5Hex(file.toFile()))));
        String uploadId=init.getJSONObject("body").getStr("upload_id");
        if(uploadId==null)throw new BizException(Error.FILE_FAILED.getCode(),"企微素材上传缺少标识");
        try(InputStream input=Files.newInputStream(file)) {
            byte[] chunk=new byte[chunkSize];int index=0,n;
            while((n=org.apache.commons.io.IOUtils.read(input,chunk))>0) {
                checked(request(WecomCommand.UPLOAD_CHUNK,UUID.randomUUID().toString(),JSONUtil.createObj().set("upload_id",uploadId)
                        .set("chunk_index",index++).set("base64_data",Base64.getEncoder().encodeToString(n==chunk.length?chunk:Arrays.copyOf(chunk,n)))));
            }
        }
        JSONObject finish=checked(request(WecomCommand.UPLOAD_FINISH,UUID.randomUUID().toString(),JSONUtil.createObj().set("upload_id",uploadId)));
        String mediaId=finish.getJSONObject("body").getStr("media_id");
        if(mediaId==null)throw new BizException(Error.FILE_FAILED.getCode(),"企微素材上传未返回文件标识");
        return mediaId;
    }
    /** 仅在业务工作线程等待素材回执，Netty I/O 线程继续处理心跳和其他机器人。 */
    private JSONObject checked(CompletionStage<JSONObject> response)throws Exception {
        JSONObject result=response.toCompletableFuture().get(10,TimeUnit.SECONDS);
        if(result.getInt("errcode",-1)!=0){
            log.warn("企微文件上传失败 botKey={}, providerCode={}",bot.getId(),result.getInt("errcode"));
            throw new BizException(Error.FILE_FAILED.getCode(),"企微文件上传被拒绝，code="+result.getInt("errcode"));
        }
        return result;
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause){
        log.warn("企微连接异常 botKey={}, connectionId={}\n{}",bot.getId(),connectionId,safeStackTrace(cause));
        ready.completeExceptionally(new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"企微连接失败",cause));close();
    }
    @Override public void channelInactive(ChannelHandlerContext ctx){if(!closed)log.warn("企微连接意外断开 botKey={}, connectionId={}",bot.getId(),connectionId);close();}
    @Override public void close(){closed=true;subscribed=false;if(heartbeat!=null)heartbeat.cancel(false);Channel c=channel;if(c!=null)c.close();ready.completeExceptionally(new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"企微连接关闭"));pending.values().forEach(f->f.completeExceptionally(new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"连接关闭")));pending.clear();}
    @Component
    @ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
    public static class Factory implements ImBotChannel.Factory {
        private final NioEventLoopGroup io=new NioEventLoopGroup(1,new DefaultThreadFactory("im-wecom-io",true));
        private final SslContext tls;
        public Factory()throws Exception{tls=SslContextBuilder.forClient().build();}
        @Override public ImBotChannel connect(ImBotEntity b,String secret,Consumer<Inbound> receive)throws Exception {
            WecomBotChannel handler=new WecomBotChannel(b,secret,receive);
            try {
                Channel c=new Bootstrap().group(io).channel(NioSocketChannel.class).option(ChannelOption.CONNECT_TIMEOUT_MILLIS,8000)
                        .handler(new ChannelInitializer<SocketChannel>(){@Override protected void initChannel(SocketChannel socket){
                            SslHandler ssl=tls.newHandler(socket.alloc(),URI_WS.getHost(),443);
                            javax.net.ssl.SSLParameters params=ssl.engine().getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");ssl.engine().setSSLParameters(params);
                            socket.pipeline().addLast(ssl,new HttpClientCodec(),new HttpObjectAggregator(65536),
                                    new ChannelOutboundHandlerAdapter(){@Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
                                        // 企微网关对这两个头的拼写敏感；标准全小写实测返回404。
                                        if(message instanceof HttpRequest) {
                                            HttpHeaders headers=((HttpRequest)message).headers();
                                            for(String name:Arrays.asList("Sec-WebSocket-Key","Sec-WebSocket-Version")) {
                                                String value=headers.get(name);if(value!=null){headers.remove(name);headers.set(name,value);}
                                            }
                                        }
                                        super.write(ctx,message,promise);
                                    }},
                                    new WebSocketClientProtocolHandler(WebSocketClientHandshakerFactory.newHandshaker(URI_WS,WebSocketVersion.V13,null,false,new DefaultHttpHeaders(),65536)),
                                    new WebSocketFrameAggregator(65536),handler);
                        }}).connect(URI_WS.getHost(),443).sync().channel();
                handler.channel=c;handler.ready.get(12,TimeUnit.SECONDS);return handler;
            }catch(Exception e){
                log.warn("企微建立连接失败 botKey={}\n{}",b.getId(),safeStackTrace(e));handler.close();
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                if(e instanceof BizException)throw e;
                if(e instanceof ExecutionException && e.getCause() instanceof BizException)throw (BizException)e.getCause();
                throw new BizException(Error.CONNECTION_UNAVAILABLE.getCode(),"企微建立连接失败",e);
            }
        }
        @PreDestroy public void close(){io.shutdownGracefully(0,5,TimeUnit.SECONDS).syncUninterruptibly();}
    }
}
