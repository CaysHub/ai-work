package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Process;
import cn.com.servyou.work.dao.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启后使用生产适配器向本人单聊发送固定验证卡片，并更新同一条消息。 */
@EnabledIfEnvironmentVariable(named="IM_STREAM_LIVE",matches="true")
class ImBotStreamingLiveIntegrationTest {
    @Test void feishuCreatesAndUpdatesOneRealCardWithReasoningAndQuestion()throws Exception {
        ImBotEntity bot=new ImBotEntity();bot.setId(Long.valueOf(required("FEISHU_BOT_KEY")));bot.setPlatform(Platform.FEISHU.getCode());bot.setExternalAppId(required("FEISHU_APP_ID"));
        ImBotSessionEntity target=new ImBotSessionEntity();target.setExternalChatId(required("FEISHU_STREAM_TARGET"));target.setSessionType(SessionType.P2P.getCode());
        FeishuBotChannel.Factory factory=new FeishuBotChannel.Factory();
        try(ImBotChannel channel=factory.connect(bot,required("FEISHU_APP_SECRET"),event->{})) {
            ImBotMessageEntity row=new ImBotMessageEntity();row.setId(1L);row.setDirection(Direction.INBOUND.getCode());row.setProviderUuid(UUID.randomUUID().toString());
            row.setProcessState(Process.RUNNING.getCode());row.setReplyContent("**流式接口验证**\n\n**思考**\n固定验证内容：开始更新同一条卡片。\n\n**回复**\n第一段。");
            ImBotChannel.Receipt first=channel.send(row,target).toCompletableFuture().get(15,TimeUnit.SECONDS);
            assertEquals(Send.ACCEPTED.getCode(),first.getState(),first.getError());assertNotNull(first.getProviderMessageId());
            row.setProviderMessageId(first.getProviderMessageId());Thread.sleep(1200);
            row.setProcessState(Process.WAITING_INPUT.getCode());row.setReplyContent("**流式接口验证**\n\n**思考**\n固定验证内容：已更新思考和正文。\n\n**回复**\n第一段，第二段。\n\n**选择展示验证，无需回答**\n1. 杭州\n2. 上海");
            ImBotChannel.Receipt second=channel.send(row,target).toCompletableFuture().get(15,TimeUnit.SECONDS);
            assertEquals(Send.ACCEPTED.getCode(),second.getState(),second.getError());assertEquals(first.getProviderMessageId(),second.getProviderMessageId());Thread.sleep(1200);
            row.setProcessState(Process.SUCCEEDED.getCode());row.setReplyContent("**流式接口验证完成**\n\n生产飞书适配器已真实创建一条卡片，并在同一消息上更新了思考、正文、编号选项和结束状态。\n\n这是一条固定协议验证消息，不是模型分析结果，也不需要回答。");
            ImBotChannel.Receipt last=channel.send(row,target).toCompletableFuture().get(15,TimeUnit.SECONDS);
            assertEquals(Send.ACCEPTED.getCode(),last.getState(),last.getError());assertEquals(first.getProviderMessageId(),last.getProviderMessageId());
            System.out.println("FEISHU_STREAM_LIVE: accepted, messageId="+last.getProviderMessageId()+", creates=1, updates=2");
        }finally{factory.close();}
    }
    private static String required(String key){String value=System.getenv(key);assertNotNull(value,key+"须从进程环境提供");return value;}
}
