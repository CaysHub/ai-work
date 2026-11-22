package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Platform;
import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 生产适配器对真实平台认证、连接和释放；不发送业务消息、不打印凭据。 */
@EnabledIfEnvironmentVariable(named="IM_PLATFORM_LIVE",matches="true")
class ImBotPlatformLiveIntegrationTest {
    @Test void realAuthenticationRejectionsUseBizCodesAndSafeLogs() throws Exception {
        String invalidSecret="validation-invalid-"+UUID.randomUUID();
        for(Platform platform:Arrays.asList(Platform.WECOM,Platform.FEISHU)) {
            Logger logger=(Logger)LoggerFactory.getLogger(platform==Platform.WECOM?WecomBotChannel.class:FeishuBotChannel.class);
            ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
            WecomBotChannel.Factory wecom=platform==Platform.WECOM?new WecomBotChannel.Factory():null;
            FeishuBotChannel.Factory feishu=platform==Platform.FEISHU?new FeishuBotChannel.Factory():null;
            try {
                ImBotChannel.Factory factory=wecom==null?feishu:wecom;
                BizException error=assertThrows(BizException.class,()->factory.connect(bot(platform==Platform.WECOM?"WECOM_BOT_ID":"FEISHU_APP_ID",platform),invalidSecret,event->{}));
                assertEquals(ImBotEnums.Error.PLATFORM_AUTH_FAILED.getCode(),error.getCode());
                assertFalse(logs.list.isEmpty());
                StringBuilder diagnostic=new StringBuilder();for(ILoggingEvent event:logs.list)diagnostic.append(event.getFormattedMessage());
                assertTrue(diagnostic.toString().contains("providerCode="));assertTrue(diagnostic.toString().contains(error.getCode()));
                assertFalse(diagnostic.toString().contains(invalidSecret));
            } finally {if(wecom!=null)wecom.close();if(feishu!=null)feishu.close();logger.detachAppender(logs);logs.stop();}
        }
    }
    @Test void wecomAuthenticationHeartbeatAndSingleIoThreadLifecycle()throws Exception {
        WecomBotChannel.Factory factory=new WecomBotChannel.Factory();
        try {
            for(int i=0;i<2;i++)try(ImBotChannel channel=factory.connect(bot("WECOM_BOT_ID",Platform.WECOM),env("WECOM_BOT_SECRET"),event->{})) {
                assertTrue(channel.online());if(i==0){Thread.sleep(32000);assertTrue(channel.online(),"真实心跳后保持连接");}
                long threads=Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive() && t.getName().startsWith("im-wecom-io")).count();assertEquals(1,threads);
            }
        } finally{factory.close();}
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive() && t.getName().startsWith("im-wecom-io")));
    }
    @Test void feishuOfficialSdkReconfigurationReleasesWorkerThreads()throws Exception {
        Set<Thread> before=Thread.getAllStackTraces().keySet();FeishuBotChannel.Factory factory=new FeishuBotChannel.Factory();
        try {
            for(int i=0;i<2;i++)try(ImBotChannel channel=factory.connect(bot("FEISHU_APP_ID",Platform.FEISHU),env("FEISHU_APP_SECRET"),event->{})){assertTrue(channel.online());}
            if(System.getenv("FEISHU_SECOND_APP_ID")!=null)try(ImBotChannel channel=factory.connect(bot("FEISHU_SECOND_APP_ID",Platform.FEISHU),env("FEISHU_SECOND_APP_SECRET"),event->{})){assertTrue(channel.online());}
        }finally{factory.close();}
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(hasNewSdkWorker(before) && System.nanoTime()<deadline)Thread.sleep(100);
        assertFalse(hasNewSdkWorker(before),"SDK启停后内部worker必须回收");
    }
    private static boolean hasNewSdkWorker(Set<Thread> before){
        for(Map.Entry<Thread,StackTraceElement[]> e:Thread.getAllStackTraces().entrySet())if(!before.contains(e.getKey()) && e.getKey().isAlive())
            for(StackTraceElement frame:e.getValue())if(frame.getClassName().startsWith("com.lark.oapi.ws."))return true;
        return false;
    }
    private static String env(String key){String value=System.getenv(key);assertNotNull(value,key+"须通过环境变量提供");return value;}
    private static ImBotEntity bot(String key,Platform platform){ImBotEntity b=new ImBotEntity();b.setExternalAppId(env(key));b.setPlatform(platform.getCode());return b;}
}
