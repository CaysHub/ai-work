package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.core.mcp.client.McpProtocolCodec;
import cn.com.servyou.work.core.audio.AudioPcmConversionService;
import cn.com.servyou.work.core.audio.AudioTranscriptionProperties;
import cn.com.servyou.work.dao.entity.ImBotMessageEntity;
import cn.com.servyou.work.dao.entity.ImBotEntity;
import cn.hutool.json.*;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.env.StandardEnvironment;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.io.InputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 显式开启的真实企微适配器读取验证；不发送消息，不启用任何持续接管。 */
@EnabledIfSystemProperty(named="im.takeover.live",matches="true")
class ImBotTakeoverPlatformLiveIntegrationTest {
    @Test void wecomDownloadsActualSpreadsheetImageAndAmrAndConvertsRecording()throws Exception {
        StandardEnvironment env=new StandardEnvironment();ImBotEntity b=new ImBotEntity();b.setPlatform(Platform.WECOM.getCode());
        b.setDocumentConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj().set(ImBotMcpCapability.MESSAGE.getCode(),required("IM_TAKEOVER_WECOM_ENDPOINT")),env));
        ImBotUserGateway gateway=new ImBotUserGateway(new ImBotWecomDocuments(new OkHttpClient(),new McpProtocolCodec(new com.fasterxml.jackson.databind.ObjectMapper())),env);
        AudioTranscriptionProperties properties=new AudioTranscriptionProperties();
        properties.setFfmpegExecutable(required("IM_TAKEOVER_FFMPEG"));
        ImBotFiles files=new ImBotFiles(null,null,null,null,null,null,null,null,env,new AudioPcmConversionService(null,properties));
        JSONArray fixtures=JSONUtil.parseArray(required("IM_TAKEOVER_WECOM_MEDIA"));Set<String> verified=new HashSet<>();
        for(Object value:fixtures) {
            JSONObject fixture=JSONUtil.parseObj(value);String type=fixture.getStr("type");Path original,converted=null;
            try(ImBotFile downloaded=gateway.download(b,new JSONObject(),new ImBotMessageEntity(),JSONUtil.createObj().set("media_id",fixture.getStr("mediaId")))) {
                original=downloaded.path();assertTrue(downloaded.getSize()>0);
                if(Wire.FILE.getCode().equals(type)) {
                    assertTrue(downloaded.getOriginalFilename().endsWith(".xlsx"));
                    try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(downloaded.getInputStream())){assertNotNull(zip.getNextEntry());}
                } else if(Wire.IMAGE.getCode().equals(type)) {
                    try(InputStream input=downloaded.getInputStream()){java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(input);assertNotNull(image);assertTrue(image.getWidth()>0);}
                } else if(Wire.VOICE.getCode().equals(type)) {
                    assertTrue(downloaded.getOriginalFilename().endsWith(".amr"));
                    try(ImBotFile pcm=files.normalizeAudio(downloaded,()->true)) {
                        converted=pcm.path();assertTrue(pcm.getOriginalFilename().endsWith(".raw"));assertTrue(pcm.getSize()>16000);
                        System.out.println("Takeover live WeCom AMR decoded to 16kHz mono PCM, bytes="+pcm.getSize());
                    }
                }
                verified.add(type);System.out.println("Takeover live WeCom media type="+type+", bytes="+downloaded.getSize()+", sends=0");
            }
            assertFalse(Files.exists(original));if(converted!=null)assertFalse(Files.exists(converted));
        }
        assertTrue(verified.containsAll(Arrays.asList(Wire.FILE.getCode(),Wire.IMAGE.getCode(),Wire.VOICE.getCode())));
    }
    @Test void wecomEmptySuccessfulWindowDoesNotStopPolling()throws Exception {
        String endpoint=required("IM_TAKEOVER_WECOM_ENDPOINT");
        StandardEnvironment env=new StandardEnvironment();ImBotEntity b=new ImBotEntity();b.setPlatform(Platform.WECOM.getCode());
        b.setDocumentConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj().set(ImBotMcpCapability.MESSAGE.getCode(),endpoint),env));
        ImBotWecomDocuments mcp=new ImBotWecomDocuments(new OkHttpClient(),new McpProtocolCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        ImBotUserGateway gateway=new ImBotUserGateway(mcp,env);
        long start=Long.parseLong(required("IM_TAKEOVER_WECOM_EMPTY_START")),end=start+5;
        for(String chat:Arrays.asList(required("IM_TAKEOVER_WECOM_PRIVATE"),required("IM_TAKEOVER_WECOM_GROUP"))){
            ImBotUserGateway.Page page=gateway.history(b,new JSONObject(),chat,start,end,null);
            assertTrue(page.getItems().isEmpty());assertNull(page.getNext());
        }
        ImBotUserGateway.Page groups=gateway.discover(b,new JSONObject(),start,end,null);
        assertTrue(groups.getItems().isEmpty());assertNull(groups.getNext());
        System.out.println("Takeover live WeCom empty window: private/group/discovery succeeded, sends=0");
    }
    @Test void wecomGatewayReadsActualPrivateAndGroupConversationsAndDiscoversGroups()throws Exception {
        String endpoint=required("IM_TAKEOVER_WECOM_ENDPOINT"),privateChat=required("IM_TAKEOVER_WECOM_PRIVATE"),group=required("IM_TAKEOVER_WECOM_GROUP");
        StandardEnvironment env=new StandardEnvironment();ImBotEntity b=new ImBotEntity();b.setPlatform(Platform.WECOM.getCode());
        b.setDocumentConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj().set(ImBotMcpCapability.MESSAGE.getCode(),endpoint),env));
        ImBotWecomDocuments mcp=new ImBotWecomDocuments(new OkHttpClient(),new McpProtocolCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
        ImBotUserGateway gateway=new ImBotUserGateway(mcp,env);long start=LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond(),end=System.currentTimeMillis()/1000;
        int total=0;for(String chat:Arrays.asList(privateChat,group)){
            ImBotUserGateway.Page page=gateway.history(b,new JSONObject(),chat,start,end,null);assertFalse(page.getItems().isEmpty());
            for(Object item:page.getItems()){
                JSONObject message=JSONUtil.parseObj(item);assertNotNull(message.getStr("sender"));assertTrue(message.getLong("time")>=start*1000);
                assertEquals(Wire.USER.getCode(),message.getStr("senderType"));
            }total+=page.getItems().size();
        }
        ImBotUserGateway.Page groups=gateway.discover(b,new JSONObject(),start,end,null);assertFalse(groups.getItems().isEmpty());
        assertTrue(groups.getItems().stream().anyMatch(value->group.equals(JSONUtil.parseObj(value).getStr("chatId"))));
        System.out.println("Takeover live WeCom readonly: private/group messages="+total+", discovered groups="+groups.getItems().size()+", sends=0");
    }
    private static String required(String name){String value=System.getenv(name);assertNotNull(value,name+"须从环境变量提供");return value;}
}
