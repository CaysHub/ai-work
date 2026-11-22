package cn.com.servyou.work.core.imbot;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 共享群按平台提及标识或明确的群内名称识别接管人。 */
final class ImBotGroupMentions {
    /** 钉钉正文中的开放钉钉号提及标记。 */
    private static final Pattern INLINE_ID = Pattern.compile("<@([^<>\\s]+)>");
    /** 点名后须结束或跟随分隔符，避免匹配到其他成员姓名的开头。 */
    private static final String NAME_END = "(?=$|[\\s\\p{Z}，、。！？；：,.!?;:()（）])";

    private ImBotGroupMentions() {}

    static boolean broadcastName(String name) {
        return "所有人".equals(name) || "全体成员".equals(name) || "全体".equals(name)
                || "all".equalsIgnoreCase(name) || "everyone".equalsIgnoreCase(name);
    }

    /** 长名称优先，避免一个带空格的长名称同时点到短名称。 */
    static Pattern names(Collection<String> names) {
        String alternatives = names.stream().filter(StringUtils::isNotBlank).filter(name -> !broadcastName(name))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return alternatives.isEmpty() ? null : Pattern.compile("(?<![A-Za-z0-9_@<.])@(" + alternatives + ")" + NAME_END);
    }

    static boolean addressed(JSONObject message, String self, String name, Pattern names) {
        JSONArray ids = message.getJSONArray("mentionedUserIds");
        if (ids != null && !ids.isEmpty()) {
            return ids.contains(self);
        }
        String text = StringUtils.defaultString(message.getStr("text"));
        Matcher inline = INLINE_ID.matcher(text);
        boolean hasInlineId = false;
        while (inline.find()) {
            hasInlineId = true;
            if (self.equals(inline.group(1))) {
                return true;
            }
        }
        if (hasInlineId || names == null || StringUtils.isBlank(name)) {
            return false;
        }
        Matcher named = names.matcher(text);
        while (named.find()) {
            if (name.equals(named.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** 飞书历史接口的提及对象使用与用户令牌匹配的 open_id。 */
    static JSONArray feishuIds(JSONArray mentions) {
        JSONArray ids = new JSONArray();
        if (mentions == null) {
            return ids;
        }
        for (Object value : mentions) {
            if (!(value instanceof JSONObject)) {
                continue;
            }
            JSONObject mention = (JSONObject) value;
            Object identity = mention.get("id");
            String type = mention.getStr("id_type");
            String id = identity instanceof JSONObject ? ((JSONObject) identity).getStr("open_id")
                    : identity instanceof String && (StringUtils.isBlank(type) || "open_id".equals(type)) ? (String) identity : null;
            if (StringUtils.isNotBlank(id)) {
                ids.add(id);
            }
        }
        return ids;
    }
}
