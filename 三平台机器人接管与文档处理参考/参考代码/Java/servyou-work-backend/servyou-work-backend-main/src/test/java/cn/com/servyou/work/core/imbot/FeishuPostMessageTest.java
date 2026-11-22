package cn.com.servyou.work.core.imbot;

import cn.hutool.json.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 依据真实飞书消息结构回归文字与附件合并发送，不模拟平台请求成功。 */
class FeishuPostMessageTest {
    @Test void actualPostShapeKeepsInstructionAndFileWithoutDuplicatingContentV2() {
        JSONObject post=JSONUtil.parseObj("{\"title\":\"\",\"content\":[[{\"tag\":\"text\",\"text\":\"整理门店汇总发我\",\"style\":[]}]],\"content_v2\":[[{\"tag\":\"text\",\"text\":\"整理门店汇总发我\",\"style\":[]}]],\"files\":[{\"file_key\":\"file_v3_example\",\"file_name\":\"门店销售情况明细.xlsx\",\"is_folder\":false}]}");
        JSONObject parsed=FeishuBotChannel.readPost(post);
        assertEquals("整理门店汇总发我",parsed.getStr("text"));assertEquals(1,parsed.getInt("fileCount"));
        assertEquals("file_v3_example",parsed.getJSONObject("file").getStr("file_key"));
        assertEquals("门店销售情况明细.xlsx",parsed.getJSONObject("file").getStr("file_name"));
    }
    @Test void localizedRichTextKeepsTitleCodeAndInlineFile() {
        JSONObject post=JSONUtil.parseObj("{\"zh_cn\":{\"title\":\"处理要求\",\"content\":[[{\"tag\":\"code_block\",\"text\":\"汇总销售额\\n\"}],[{\"tag\":\"file\",\"file_key\":\"file_inline\",\"file_name\":\"明细.xlsx\"}]]}}");
        JSONObject parsed=FeishuBotChannel.readPost(post);
        assertEquals("处理要求\n汇总销售额",parsed.getStr("text"));assertEquals(1,parsed.getInt("fileCount"));
        assertEquals("file_inline",parsed.getJSONObject("file").getStr("file_key"));
    }
    @Test void repeatedFileReferencesAreCountedOnceAndMultipleFilesAreNotSilentlyTruncated() {
        JSONObject post=JSONUtil.parseObj("{\"content\":[[{\"tag\":\"file\",\"file_key\":\"f1\",\"file_name\":\"一.xlsx\"}]],\"files\":[{\"file_key\":\"f1\",\"file_name\":\"一.xlsx\"},{\"file_key\":\"f2\",\"file_name\":\"二.xlsx\"}]}");
        assertEquals(2,FeishuBotChannel.readPost(post).getInt("fileCount"));
        post.getJSONArray("files").remove(1);post.getJSONArray("content").getJSONArray(0).getJSONObject(0).remove("file_name");
        JSONObject parsed=FeishuBotChannel.readPost(post);assertEquals(1,parsed.getInt("fileCount"));
        assertEquals("一.xlsx",parsed.getJSONObject("file").getStr("file_name"),"正文重复引用不得覆盖附件列表中的真实文件名");
    }
}
