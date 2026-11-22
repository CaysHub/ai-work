package cn.com.servyou.work.api.enums;

import cn.com.servyou.work.api.exception.BizException;
import lombok.Getter;

/** 企微机器人官方授权能力类别，代码与官方固定入口对应。 */
@Getter
public enum ImBotMcpCapability {
    DOCUMENT("doc"), MESSAGE("msg"), TODO("todo"), SCHEDULE("schedule"),
    MEETING("meeting"), DISK("disk"), CONTACT("contact");

    /** 官方入口类别代码，不由客户端提供任意服务地址。 */
    private final String code;

    ImBotMcpCapability(String code) { this.code=code; }

    /** 校验并解析固定的七种能力。 */
    public static ImBotMcpCapability fromCode(String code) {
        for(ImBotMcpCapability value:values())if(value.code.equals(code))return value;
        throw BizException.paramError("不支持的企微能力类别");
    }
}
