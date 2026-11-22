package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.hutool.json.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** 验证平台媒体结构、群聊触发边界与实际文件头路由，不建立连接或发送消息。 */
class ImBotMediaTest {
    @Test void wecomVoiceUsesPlatformTranscriptAndPureMediaCannotTriggerGroups() {
        JSONObject body=JSONUtil.createObj().set("msgtype",Wire.VOICE.getCode()).set("msgid","voice-message")
                .set("from",JSONUtil.createObj().set("userid","speaker")).set("chattype","single")
                .set("voice",JSONUtil.createObj().set("content","请分析杭州销售情况"));
        ImBotChannel.Inbound voice=WecomBotChannel.readMessage(body,"connection","req");
        assertEquals(Content.TEXT.getCode(),voice.getContentType());assertEquals("请分析杭州销售情况",voice.getContent());
        assertEquals("speaker",voice.getExternalUserId());
        body.set("chattype",Wire.GROUP.getCode());assertNull(WecomBotChannel.readMessage(body,"c","r"));
        for(Wire type:Arrays.asList(Wire.IMAGE,Wire.FILE)) {
            body.set("msgtype",type.getCode()).set(type.getCode(),JSONUtil.createObj().set("url","https://example.invalid/media").set("aeskey","test-key"));
            assertNull(WecomBotChannel.readMessage(body,"c","r"));
            body.set("chattype","single");ImBotChannel.Inbound media=WecomBotChannel.readMessage(body,"c","r");
            assertEquals(Content.FILE.getCode(),media.getContentType());assertNotNull(JSONUtil.parseObj(media.getContext()).getJSONObject("file"));
            body.set("chattype",Wire.GROUP.getCode());
        }
    }
    @Test void wecomMixedContentKeepsPromptAndReportsEveryAttachment() {
        JSONArray items=new JSONArray().put(JSONUtil.createObj().set("msgtype",Wire.TEXT.getCode()).set("text",JSONUtil.createObj().set("content","@助手 看一下这张图")));
        items.add(JSONUtil.createObj().set("msgtype",Wire.IMAGE.getCode()).set("image",JSONUtil.createObj().set("url","https://example.invalid/1").set("aeskey","test-key")));
        JSONObject body=JSONUtil.createObj().set("msgtype",Wire.MIXED.getCode()).set("mixed",JSONUtil.createObj().set("msg_item",items))
                .set("from",JSONUtil.createObj().set("userid","speaker")).set("chattype",Wire.GROUP.getCode()).set("chatid","group");
        ImBotChannel.Inbound media=WecomBotChannel.readMessage(body,"c","r");JSONObject context=JSONUtil.parseObj(media.getContext());
        assertEquals(SessionType.GROUP.getCode(),media.getSessionType());assertEquals(1,context.getInt("fileCount").intValue());
        assertEquals("@助手 看一下这张图",context.getStr("prompt"));
        items.add(JSONUtil.createObj().set("msgtype",Wire.IMAGE.getCode()).set("image",JSONUtil.createObj().set("url","https://example.invalid/2")));
        assertEquals(2,JSONUtil.parseObj(WecomBotChannel.readMessage(body,"c","r").getContext()).getInt("fileCount").intValue());
    }
    @Test void feishuImagesAudioAndRichPostsRetainResourceKeysAndText() {
        JSONObject image=FeishuBotChannel.readContent(Wire.IMAGE.getCode(),JSONUtil.createObj().set("image_key","img_test"));
        assertEquals("img_test",image.getJSONObject("file").getStr("image_key"));assertEquals(Wire.IMAGE.getCode(),image.getJSONObject("file").getStr("resourceType"));
        JSONObject audio=FeishuBotChannel.readContent(Wire.AUDIO.getCode(),JSONUtil.createObj().set("file_key","file_audio").set("duration",3000));
        assertEquals("file_audio",audio.getJSONObject("file").getStr("file_key"));assertEquals(Wire.FILE.getCode(),audio.getJSONObject("file").getStr("resourceType"));
        JSONObject post=JSONUtil.parseObj("{\"zh_cn\":{\"title\":\"图片分析\",\"content\":[[{\"tag\":\"text\",\"text\":\"检查金额\"},{\"tag\":\"img\",\"image_key\":\"img_test\"}]]}}");
        JSONObject rich=FeishuBotChannel.readContent(Wire.POST.getCode(),post);
        assertEquals(1,rich.getInt("fileCount").intValue());assertEquals("img_test",rich.getJSONObject("file").getStr("image_key"));assertTrue(rich.getStr("text").contains("检查金额"));
    }
    @Test void missingMediaNamesAreResolvedFromBytesInsteadOfRemainingBinary()throws Exception {
        byte[] png=new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0,0,0,0,0,0,0,0};
        try(ImBotFile file=ImBotFile.copyOf(new ByteArrayInputStream(png),null,"application/octet-stream",100)) {
            assertTrue(file.getOriginalFilename().endsWith(".png"));assertArrayEquals(png,file.getBytes());
        }
        byte[] ogg="OggS\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000OpusHead".getBytes(StandardCharsets.ISO_8859_1);
        try(ImBotFile file=ImBotFile.copyOf(new ByteArrayInputStream(ogg),"download","audio/ogg",100)) {
            assertTrue(file.getOriginalFilename().endsWith(".ogg"));
        }
    }
}
