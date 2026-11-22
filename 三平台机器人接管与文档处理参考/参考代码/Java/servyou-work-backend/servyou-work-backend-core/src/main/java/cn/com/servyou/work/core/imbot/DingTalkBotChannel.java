package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.enums.ImBotEnums.Content;
import cn.com.servyou.work.api.enums.ImBotEnums.Send;
import cn.com.servyou.work.api.enums.ImBotEnums.SessionType;
import cn.com.servyou.work.api.enums.ImBotEnums.Wire;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.com.servyou.work.dao.entity.ImBotMessageEntity;
import cn.com.servyou.work.dao.entity.ImBotSessionEntity;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 钉钉企业内部应用 Stream 机器人。节点租约、消息去重和自动处理复用公共流程。 */
@Slf4j
final class DingTalkBotChannel extends WebSocketListener implements ImBotChannel {

    private static final String API = "https://api.dingtalk.com";
    private static final String BOT_MESSAGES = "/v1.0/im/bot/messages/get";
    private static final String TOKEN_PATH = "/v1.0/oauth2/accessToken";
    private static final String STREAM_PATH = "/v1.0/gateway/connections/open";

    private final Long botKey;
    private final String clientId;
    private final String secret;
    private final Consumer<Inbound> receive;
    private final ThreadPoolTaskExecutor callbackExecutor;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile WebSocket socket;
    private volatile boolean online;
    private volatile boolean closed;
    // 仅由本机器人串行发送线程读写。
    private String token;
    private long tokenExpiresAt;

    private DingTalkBotChannel(ImBotEntity bot, String secret, Consumer<Inbound> receive,
                               ThreadPoolTaskExecutor callbackExecutor) {
        this.botKey = bot.getId();
        this.clientId = bot.getExternalAppId();
        this.secret = secret;
        this.receive = receive;
        this.callbackExecutor = callbackExecutor;
    }

    @Override public boolean online() {
        return online && !closed;
    }

    @Override public void onOpen(WebSocket ws, Response response) {
        if (closed) {
            ws.cancel();
            return;
        }
        socket = ws;
        online = true;
        ready.complete(null);
    }

    @Override public void onMessage(WebSocket ws, String message) {
        if (!online()) return;
        try {
            JSONObject frame = JSONUtil.parseObj(message);
            JSONObject headers = frame.getJSONObject("headers");
            String deliveryId = headers == null ? null : headers.getStr("messageId");
            if (StringUtils.isBlank(deliveryId)) return;
            String topic = headers.getStr("topic");
            if ("SYSTEM".equals(frame.getStr("type"))) {
                acknowledge(ws, deliveryId, 200, frame.getStr("data", "{}"));
                if ("disconnect".equals(topic)) close();
                return;
            }
            if (!"CALLBACK".equals(frame.getStr("type")) || !BOT_MESSAGES.equals(topic)) return;
            Inbound event = inbound(JSONUtil.parseObj(frame.getStr("data")), clientId);
            if (event == null) {
                acknowledge(ws, deliveryId, 200, "{}");
                return;
            }
            Thread ioThread = Thread.currentThread();
            try {
                callbackExecutor.execute(() -> {
                    // 共用池满时，拒绝 CallerRunsPolicy 将入库操作转给 WebSocket 线程。
                    if (Thread.currentThread() == ioThread) throw new RejectedExecutionException();
                    try {
                        receive.accept(event);
                        acknowledge(ws, deliveryId, 200, "{}");
                    } catch (RuntimeException e) {
                        log.warn("钉钉回调入库失败 botKey={}, deliveryId={}\n{}", botKey, deliveryId, safeStackTrace(e));
                        acknowledge(ws, deliveryId, 500, "{}");
                    }
                });
            } catch (RejectedExecutionException busy) {
                log.warn("钉钉回调工作池已满 botKey={}, deliveryId={}", botKey, deliveryId);
                acknowledge(ws, deliveryId, 500, "{}");
            }
        } catch (RuntimeException e) {
            log.warn("钉钉 Stream 消息格式异常 botKey={}\n{}", botKey, safeStackTrace(e));
        }
    }

    /** 将平台会话标识和员工账号转换成公共消息结构。 */
    static Inbound inbound(JSONObject data, String clientId) {
        if (!Wire.TEXT.getCode().equals(data.getStr("msgtype"))) return null;
        String robotCode = data.getStr("robotCode");
        if (StringUtils.isNotBlank(robotCode) && !clientId.equals(robotCode)) return null;
        String conversationType = data.getStr("conversationType");
        if (!"1".equals(conversationType) && !"2".equals(conversationType)) return null;
        boolean group = "2".equals(conversationType);
        // 钉钉群聊仅交付 @ 机器人的消息，显式非 @ 事件不触发处理。
        if (group && Boolean.FALSE.equals(data.getBool("isInAtList"))) return null;
        String sender = data.getStr("senderStaffId");
        String chat = group ? data.getStr("conversationId") : sender;
        String messageId = data.getStr("msgId");
        JSONObject text = data.getJSONObject("text");
        String content = text == null ? null : StringUtils.trimToNull(text.getStr("content"));
        if (StringUtils.isAnyBlank(sender, chat, messageId, content) || content.length() > 20000) return null;
        Inbound result = new Inbound();
        result.setExternalUserId(sender);
        result.setChatId(chat);
        result.setSessionType(group ? SessionType.GROUP.getCode() : SessionType.P2P.getCode());
        result.setMessageId(messageId);
        result.setContentType(Content.TEXT.getCode());
        result.setContent(content);
        return result;
    }

    private static void acknowledge(WebSocket ws, String deliveryId, int code, String data) {
        JSONObject headers = JSONUtil.createObj().set("messageId", deliveryId).set("contentType", "application/json");
        ws.send(JSONUtil.createObj().set("code", code).set("headers", headers)
                .set("message", code == 200 ? "OK" : "Callback not committed").set("data", data).toString());
    }

    @Override public void onClosing(WebSocket ws, int code, String reason) {
        online = false;
        ws.close(1000, "");
    }

    @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
        online = false;
        if (!closed) {
            log.warn("钉钉 Stream 连接中断 botKey={}, httpStatus={}\n{}", botKey,
                    response == null ? null : response.code(), safeStackTrace(error));
        }
        ready.completeExceptionally(failed("钉钉 Stream 连接失败"));
    }

    @Override public void close() {
        closed = true;
        online = false;
        WebSocket ws = socket;
        if (ws != null) ws.cancel();
        ready.completeExceptionally(failed("钉钉 Stream 已关闭"));
    }

    @Override public CompletionStage<Receipt> send(ImBotMessageEntity message, ImBotSessionEntity target) {
        String accessToken;
        try {
            accessToken = accessToken();
        } catch (Exception e) {
            log.warn("钉钉应用鉴权失败 botKey={}\n{}", botKey, safeStackTrace(e));
            return CompletableFuture.completedFuture(new Receipt(Send.FAILED.getCode(), null, "钉钉应用鉴权失败"));
        }
        boolean group = SessionType.GROUP.getCode().equals(target.getSessionType());
        String text = StringUtils.defaultIfBlank(message.getReplyContent(), message.getContent());
        if (StringUtils.isBlank(text)) return CompletableFuture.completedFuture(new Receipt(Send.FAILED.getCode(), null, "消息正文为空"));
        JSONObject body = JSONUtil.createObj().set("robotCode", clientId).set("msgKey", "sampleText")
                .set("msgParam", JSONUtil.createObj().set("content", ImBotContent.abbreviate(text, 1500)).toString());
        if (group) body.set("openConversationId", target.getExternalChatId());
        else body.set("userIds", Collections.singletonList(target.getExternalChatId()));
        String path = group ? "/v1.0/robot/groupMessages/send" : "/v1.0/robot/oToMessages/batchSend";
        Receipt receipt;
        try {
            OkHttpClientUtil.HttpResult res = OkHttpClientUtil.jsonNoRetry("POST", API + path,
                    Collections.singletonMap("x-acs-dingtalk-access-token", accessToken), body.toString());
            JSONObject data = JSONUtil.parseObj(StringUtils.defaultIfBlank(res.getBody(), "{}"));
            if (!res.isSuccessful() || data.containsKey("code") || hasInvalidUsers(data)) {
                if (res.getStatusCode() == 401) tokenExpiresAt = 0;
                log.warn("钉钉拒绝消息 botKey={}, httpStatus={}", botKey, res.getStatusCode());
                receipt = new Receipt(Send.FAILED.getCode(), null, "钉钉拒绝发送，请检查机器人权限和会话");
            } else {
                receipt = new Receipt(Send.ACCEPTED.getCode(), data.getStr("processQueryKey"), null);
            }
        } catch (Exception e) {
            log.warn("钉钉发送结果未确认 botKey={}\n{}", botKey, safeStackTrace(e));
            receipt = new Receipt(Send.UNKNOWN.getCode(), null, "钉钉发送结果未确认");
        }
        return CompletableFuture.completedFuture(receipt);
    }

    private static boolean hasInvalidUsers(JSONObject data) {
        JSONArray invalid = data.getJSONArray("invalidStaffIdList");
        return invalid != null && !invalid.isEmpty();
    }

    private String accessToken() throws IOException {
        long now = System.currentTimeMillis();
        if (StringUtils.isNotBlank(token) && tokenExpiresAt > now) return token;
        JSONObject data = post(TOKEN_PATH, JSONUtil.createObj().set("appKey", clientId).set("appSecret", secret));
        token = data.getStr("accessToken");
        if (StringUtils.isBlank(token)) throw failed("钉钉应用授权响应缺少令牌");
        tokenExpiresAt = now + Math.max(0, data.getLong("expireIn", 0L) - 60) * 1000;
        return token;
    }

    private static JSONObject post(String path, JSONObject body) throws IOException {
        OkHttpClientUtil.HttpResult result = OkHttpClientUtil.jsonNoRetry("POST", API + path, Collections.emptyMap(), body.toString());
        if (!result.isSuccessful()) throw failed("钉钉平台请求失败，HTTP " + result.getStatusCode());
        return JSONUtil.parseObj(result.getBody());
    }

    private static BizException failed(String message) {
        return new BizException(ImBotEnums.Error.PLATFORM_REQUEST_FAILED.getCode(), message);
    }

    /** 由现有 Bot 租约创建连接，所有钉钉机器人共用一个传输客户端。 */
    @Component
    @ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
    static class Factory implements ImBotChannel.Factory {
        private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false)
                .pingInterval(30, TimeUnit.SECONDS).build();
        private final ThreadPoolTaskExecutor callbacks;

        Factory(@Qualifier("taskExecutor") ThreadPoolTaskExecutor callbacks) {
            this.callbacks = callbacks;
        }

        @PreDestroy
        void shutdown() {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }

        @Override public ImBotChannel connect(ImBotEntity bot, String secret, Consumer<Inbound> receive) throws Exception {
            JSONObject subscription = JSONUtil.createObj().set("type", "CALLBACK").set("topic", BOT_MESSAGES);
            JSONObject target = post(STREAM_PATH, JSONUtil.createObj().set("clientId", bot.getExternalAppId())
                    .set("clientSecret", secret).set("subscriptions", Collections.singletonList(subscription)));
            String ticket = target.getStr("ticket");
            String endpoint = target.getStr("endpoint", "");
            if (!endpoint.startsWith("wss://") || StringUtils.isBlank(ticket)) throw failed("钉钉 Stream 连接票据缺失");
            HttpUrl url = HttpUrl.parse("https://" + endpoint.substring("wss://".length()));
            if (url == null || !(url.host().equals("dingtalk.com") || url.host().endsWith(".dingtalk.com"))
                    || !url.username().isEmpty() || !url.password().isEmpty()) throw failed("钉钉 Stream 网关地址不合法");
            DingTalkBotChannel channel = new DingTalkBotChannel(bot, secret, receive, callbacks);
            channel.socket = client.newWebSocket(new Request.Builder().url(url.newBuilder().setQueryParameter("ticket", ticket).build()).build(), channel);
            try {
                channel.ready.get(10, TimeUnit.SECONDS);
                return channel;
            } catch (Exception e) {
                channel.close();
                throw e;
            }
        }
    }
}
