package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.dao.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 仅显式开启时向已授权的本人企微单聊发送固定验证文件，需先暂停原后端连接。 */
@EnabledIfEnvironmentVariable(named="IM_DOCUMENT_LIVE",matches="true")
class ImBotDocumentLiveIntegrationTest {
    @Test void wecomUploadsRealChunksAndSendsNativeDocument()throws Exception {
        ImBotEntity bot=new ImBotEntity();bot.setId(Long.valueOf(required("WECOM_BOT_KEY")));bot.setExternalAppId(required("WECOM_BOT_ID"));bot.setPlatform(Platform.WECOM.getCode());
        ImBotSessionEntity target=new ImBotSessionEntity();target.setExternalChatId(required("WECOM_DOCUMENT_TARGET"));target.setSessionType(SessionType.P2P.getCode());
        Path file=Files.createTempFile("im-real-document-",".txt");
        try {
            // 超过一个分片，验证真正的多片上传和平台合并，内容仅为固定验证文本。
            StringBuilder text=new StringBuilder("企业机器人文档收发接口验证\n");
            while(text.length()<300000)text.append("这是一份固定的接口验证文档，不包含业务数据。\n");
            Files.write(file,text.toString().getBytes(StandardCharsets.UTF_8));
            WecomBotChannel.Factory factory=new WecomBotChannel.Factory();
            try(ImBotChannel channel=factory.connect(bot,required("WECOM_BOT_SECRET"),event->{})) {
                ImBotMessageEntity message=new ImBotMessageEntity();message.setId(1L);message.setDirection(Direction.OUTBOUND.getCode());message.setContentType(Content.FILE.getCode());message.setProviderUuid(UUID.randomUUID().toString());
                ImBotChannel.Receipt receipt=channel.sendFile(message,target,file,"企微文档接口验证.txt").toCompletableFuture().get(15,TimeUnit.SECONDS);
                assertEquals(Send.ACCEPTED.getCode(),receipt.getState(),receipt.getError());
                System.out.println("WECOM_DOCUMENT_LIVE: native file accepted, bytes="+Files.size(file));
            } finally{factory.close();}
        } finally{Files.deleteIfExists(file);}
    }
    private static String required(String key){String value=System.getenv(key);assertNotNull(value,key+"须从进程环境提供");return value;}
}
