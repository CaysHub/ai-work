package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.SessionType;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;

class DingTalkBotChannelTest {

    @Test void directReplyUsesStaffIdentifier() {
        ImBotChannel.Inbound event = DingTalkBotChannel.inbound(message().set("conversationType", "1"), "ding-app");
        assertNotNull(event);
        assertEquals(SessionType.P2P.getCode(), event.getSessionType());
        assertEquals("staff-member", event.getChatId());
        assertEquals("staff-member", event.getExternalUserId());
        assertEquals("message-id", event.getMessageId());
        assertEquals("绑定 A123456789ABCDEF", event.getContent());
    }

    @Test void groupReplyUsesOpenConversationIdAndRejectsNonMention() {
        JSONObject payload = message().set("conversationType", "2").set("isInAtList", true);
        ImBotChannel.Inbound event = DingTalkBotChannel.inbound(payload, "ding-app");
        assertNotNull(event);
        assertEquals(SessionType.GROUP.getCode(), event.getSessionType());
        assertEquals("cid-group", event.getChatId());
        assertNull(DingTalkBotChannel.inbound(payload.set("isInAtList", false), "ding-app"));
        assertNull(DingTalkBotChannel.inbound(payload, "another-robot"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DINGTALK_STREAM_SECRET", matches = ".+")
    void realStreamHandshakeThroughProductionChannel() throws Exception {
        ThreadPoolTaskExecutor callbacks = new ThreadPoolTaskExecutor();
        callbacks.initialize();
        DingTalkBotChannel.Factory factory = new DingTalkBotChannel.Factory(callbacks);
        ImBotEntity bot = new ImBotEntity();
        bot.setExternalAppId(System.getenv("DINGTALK_STREAM_CLIENT_ID"));
        try (ImBotChannel channel = factory.connect(bot, System.getenv("DINGTALK_STREAM_SECRET"), event -> {
            // 连接验证期间遇到业务消息时不确认投递。
            throw new IllegalStateException("连接验证未消费业务消息");
        })) {
            assertTrue(channel.online());
        } finally {
            factory.shutdown();
            callbacks.shutdown();
        }
    }

    private JSONObject message() {
        return JSONUtil.createObj().set("msgtype", "text").set("robotCode", "ding-app")
                .set("msgId", "message-id").set("senderStaffId", "staff-member").set("conversationId", "cid-group")
                .set("text", JSONUtil.createObj().set("content", "  绑定 A123456789ABCDEF "));
    }
}
