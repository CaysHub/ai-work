package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.enums.ImBotEnums.Send;
import cn.com.servyou.work.api.enums.ImBotEnums.SessionType;
import cn.com.servyou.work.api.enums.ImBotEnums.Wire;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 钉钉 Workspace 个人消息适配，使用官方 HTTP 工具和已有接管水位。 */
@Slf4j
final class DingTalkUserMessages {
    /** 钉钉消息历史的时间使用北京时间，活跃会话列表使用带时区的 ISO 格式。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String HOST = "https://mcp-gw.dingtalk.com/server/";
    private static final String CONTACT = "db4b26cb38ea6a8739ad55d1997fa1da608cd36b33a6cf0f77884f70c49382fe";
    private static final String IM = "450eede6b54d83e030140e66ec77c98a2e89a0869ef4db481f8217a98a42f821";
    private static final String CHAT = "0a1609437385696b77fc4771c3ddaf5656b487f809966c0cc8d4755e7b1d3b74";

    /** 服务地址及工具固定在后端，禁止调用方选任意端点或操作。 */
    @Getter
    @RequiredArgsConstructor
    enum Tool {
        CURRENT_USER(CONTACT, "get_current_user_profile"),
        CHATS(IM, "list_all_conversations"),
        HISTORY(CHAT, "list_conversation_message_v2"),
        SEND(CHAT, "send_personal_message"),
        RECEIPT(IM, "query_message_send_status");

        /** 官方工具服务标识。 */
        private final String server;
        /** 官方工具名称。 */
        private final String code;
    }

    /** 投递回执中会结束本次查询的状态。 */
    @Getter
    @RequiredArgsConstructor
    private enum Delivery {
        SUCCESS("SUCCESS"), FAILED("FAILED");

        /** 钉钉投递接口状态值。 */
        private final String code;
    }

    private DingTalkUserMessages() {
    }

    static ImBotUserGateway.Page discover(JSONObject config, long start, String cursor) throws IOException {
        JSONObject arguments = JSONUtil.createObj().set("limit", 30);
        if (cursor != null) {
            arguments.set("cursor", Long.parseLong(cursor));
        }
        JSONObject data = result(Tool.CHATS, config, arguments);
        JSONArray items = data.getJSONArray("conversations");
        if (items == null) {
            throw invalid("钉钉会话列表响应缺少会话数据");
        }
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id = item.getStr("openConversationId");
            String latest = item.getStr("lastMsgCreateAt");
            Boolean single = item.getBool("singleChat");
            if (StringUtils.isAnyBlank(id, latest) || single == null) {
                continue;
            }
            long time = timestamp(latest) / 1000;
            if (time >= start) {
                normalized.add(JSONUtil.createObj().set("chatId", id).set("name", item.getStr("title"))
                        .set("type", single ? SessionType.P2P.getCode() : SessionType.GROUP.getCode()));
            }
        }
        return new ImBotUserGateway.Page(normalized, next(data, cursor, false));
    }

    static ImBotUserGateway.Page history(JSONObject config, String chat, long start, long end, String cursor) throws IOException {
        long upper = end * 1000 + 999;
        String boundary = StringUtils.defaultIfBlank(cursor, String.valueOf(upper));
        JSONObject arguments = JSONUtil.createObj().set("openconversation_id", chat).set("time", boundary)
                .set("forward", false).set("limit", 50).set("mark_as_read", false);
        JSONObject data = result(Tool.HISTORY, config, arguments);
        return historyPage(data, chat, start * 1000, upper, boundary);
    }

    static ImBotUserGateway.Page historyPage(JSONObject data, String chat, long start, long end, String boundary) {
        JSONArray messages = data.getJSONArray("messages");
        if (messages == null) {
            throw invalid("钉钉历史响应缺少消息列表");
        }
        JSONArray normalized = new JSONArray();
        boolean reachedStart = false;
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            JSONObject item = messages.getJSONObject(i);
            long time = timestamp(item.getStr("createTime"));
            if (time > previous) {
                throw invalid("钉钉历史消息排序与请求不一致");
            }
            previous = time;
            if (time < start) {
                reachedStart = true;
                continue;
            }
            if (time > end || !Wire.USER.getCode().equals(item.getStr("sendType"))) {
                continue;
            }
            String sender = item.getStr("senderOpenDingTalkId");
            String id = item.getStr("openMessageId");
            if (StringUtils.isAnyBlank(sender, id) || !chat.equals(item.getStr("openConversationId"))) {
                throw invalid("钉钉历史消息缺少可核验的会话、发送人或消息标识");
            }
            normalized.add(JSONUtil.createObj().set("id", id).set("sender", sender).set("time", time)
                    .set("senderType", Wire.USER.getCode()).set("type", Wire.TEXT.getCode()).set("text", item.getStr("content", ""))
                    .set("mentionedUserIds", item.get("atOpenDingTalkIds") instanceof JSONArray ? item.getJSONArray("atOpenDingTalkIds") : null)
                    .set("aiSent", StringUtils.isNotBlank(item.getStr("messageAiSendFlag"))));
        }
        return new ImBotUserGateway.Page(normalized, reachedStart ? null : next(data, boundary, true));
    }

    /** 一次发送后核对回执，保留平台 UUID 去重，未确认时交给现有 UNKNOWN 处理。 */
    static ImBotChannel.Receipt send(JSONObject config, String chat, String text, String uuid) throws IOException, InterruptedException {
        JSONObject arguments = JSONUtil.createObj().set("openConversationId", chat).set("uuid", uuid)
                .set("msgType", Wire.TEXT.getCode()).set("content", JSONUtil.createObj().set("content", text).toString());
        JSONObject data = result(Tool.SEND, config, arguments);
        String messageId = data.getStr("openMessageId");
        if (StringUtils.isNotBlank(messageId) && chat.equals(data.getStr("openConversationId"))) {
            return new ImBotChannel.Receipt(Send.ACCEPTED.getCode(), messageId, null);
        }
        String taskId = data.getStr("openTaskId");
        if (StringUtils.isNotBlank(taskId)) {
            JSONObject query = JSONUtil.createObj().set("openTaskId", taskId);
            // 发送接口先返回任务号，紧接着查询时投递任务可能尚不可见。
            for (int attempt = 0; attempt < 3; attempt++) {
                TimeUnit.SECONDS.sleep(1);
                JSONObject receipt = result(Tool.RECEIPT, config, query);
                messageId = receipt.getStr("openMessageId");
                if (Delivery.SUCCESS.getCode().equals(receipt.getStr("sendStatus")) && chat.equals(receipt.getStr("openConversationId"))
                        && StringUtils.isNotBlank(messageId)) {
                    return new ImBotChannel.Receipt(Send.ACCEPTED.getCode(), messageId, null);
                }
                if (Delivery.FAILED.getCode().equals(receipt.getStr("sendStatus"))) {
                    log.warn("钉钉真人消息投递失败 chatId={}", chat);
                    return new ImBotChannel.Receipt(Send.FAILED.getCode(), null, "钉钉消息投递失败");
                }
            }
        }
        log.warn("钉钉真人消息投递未确认 chatId={}", chat);
        return new ImBotChannel.Receipt(Send.UNKNOWN.getCode(), null, "钉钉未确认消息投递结果");
    }

    private static JSONObject result(Tool tool, JSONObject config, JSONObject args) throws IOException {
        JSONObject data = call(tool, config.getStr("accessToken"), args).getJSONObject("result");
        if (data == null) {
            throw invalid("钉钉工具响应缺少业务数据");
        }
        return data;
    }

    static JSONObject call(Tool tool, String accessToken, JSONObject args) throws IOException {
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(), "钉钉接管需要本人授权");
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("x-user-access-token", accessToken);
        JSONObject request = JSONUtil.createObj().set("jsonrpc", "2.0").set("id", 1).set("method", "tools/call")
                .set("params", JSONUtil.createObj().set("name", tool.getCode()).set("arguments", args));
        OkHttpClientUtil.HttpResult http = OkHttpClientUtil.jsonNoRetry(
                "POST", HOST + tool.getServer(), headers, request.toString());
        JSONObject response = JSONUtil.isTypeJSONObject(http.getBody()) ? JSONUtil.parseObj(http.getBody()) : new JSONObject();
        JSONObject envelope = response.getJSONObject("result");
        JSONObject payload = envelope == null || Boolean.TRUE.equals(envelope.getBool("isError")) ? null : toolPayload(envelope);
        if (!http.isSuccessful() || !Objects.equals(response.getInt("id"), 1) || payload == null
                || !Boolean.TRUE.equals(payload.getBool("success"))) {
            JSONObject error = response.getJSONObject("error");
            String code = payload != null ? payload.getStr("errorCode") : error == null ? null : error.getStr("code");
            log.warn("钉钉 Workspace 工具请求失败 tool={}, httpStatus={}, providerCode={}", tool.getCode(), http.getStatusCode(),
                    code != null && code.matches("[A-Za-z0-9_.-]{1,80}") ? code : "");
            throw invalid("钉钉个人接口请求失败，请核对会话和本人权限");
        }
        return payload;
    }

    private static JSONObject toolPayload(JSONObject envelope) {
        JSONObject payload = envelope.getJSONObject("structuredContent");
        if (payload != null) {
            return payload;
        }
        JSONArray content = envelope.getJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.size(); i++) {
                JSONObject block = content.getJSONObject(i);
                if (JSONUtil.isTypeJSONObject(block.getStr("text"))) {
                    return JSONUtil.parseObj(block.getStr("text"));
                }
            }
        }
        return null;
    }

    private static String next(JSONObject data, String current, boolean backward) {
        Boolean hasMore = data.getBool("hasMore");
        if (hasMore == null) {
            throw invalid("钉钉响应缺少分页状态");
        }
        if (!hasMore) {
            return null;
        }
        Long cursor = data.getLong("nextCursor");
        if (cursor == null || cursor <= 0 || (current != null && (backward
                ? cursor >= Long.parseLong(current) : cursor.equals(Long.valueOf(current))))) {
            throw invalid("钉钉响应缺少可推进的分页游标");
        }
        return String.valueOf(cursor);
    }

    static long timestamp(String value) {
        if (StringUtils.isBlank(value)) {
            throw invalid("钉钉消息缺少发送时间");
        }
        if (value.indexOf('T') >= 0) {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        }
        return LocalDateTime.parse(value, TIME).atZone(CHINA).toInstant().toEpochMilli();
    }

    private static BizException invalid(String message) {
        return new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(), message);
    }
}
