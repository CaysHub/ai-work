package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.exception.BizException;
import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;

/** 两平台首版采用保守的单条上限，超长自动答复指向原会话。 */
public final class ImBotContent {
    private ImBotContent() { }
    public static void validate(String platform,String type,String content) {
        if(!Content.TEXT.getCode().equals(type) && !(Platform.WECOM.getCode().equals(platform) && Content.MARKDOWN.getCode().equals(type)))
            throw BizException.paramError("平台不支持此内容类型");
        if(StrUtil.isBlank(content) || content.getBytes(StandardCharsets.UTF_8).length>16000) throw BizException.paramError("消息不能为空且不能超过16000个UTF-8字节");
        if(Platform.WECOM.getCode().equals(platform) && Content.TEXT.getCode().equals(type)
                && wecomText(content).getBytes(StandardCharsets.UTF_8).length>20480) throw BizException.paramError("纯文本转义后超过企微单条限制，请缩短正文");
    }
    public static String wecomText(String text) {
        return text.replaceAll("([\\\\`*_{}\\[\\]()#+.!|>~-])","\\\\$1");
    }
    /** 复用已有 Unicode code point 截断逻辑，避免正文与思考切断代理对。 */
    static String abbreviate(String value,int limit) {
        int count=value.codePointCount(0,value.length());
        return count<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit))+"…";
    }
    public static String answer(String content,String sessionId) {
        String value=StrUtil.blankToDefault(content,"本轮已结束，请在工作台查看完整结果。");
        if(value.getBytes(StandardCharsets.UTF_8).length<=16000) return value;
        String suffix="\n\n[答复已截断，请在工作台会话 "+sessionId+" 查看完整结果]";
        // 按 Unicode code point 截断，避免断开代理对和 UTF-8 字符。
        return abbreviate(value,3500)+suffix;
    }
}
