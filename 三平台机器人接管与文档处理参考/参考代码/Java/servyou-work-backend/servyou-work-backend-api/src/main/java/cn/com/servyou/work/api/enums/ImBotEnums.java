package cn.com.servyou.work.api.enums;

import cn.com.servyou.work.api.exception.BizException;
import lombok.Getter;

/** 机器人领域代码；进入 Mapper 前转换为代码值。 */
public final class ImBotEnums {
    private ImBotEnums() { }
    public interface Code { String getCode(); }
    public static <E extends Enum<E> & Code> E require(Class<E> type, String code) {
        for (E value : type.getEnumConstants()) if (value.getCode().equals(code)) return value;
        throw BizException.paramError("不支持的" + type.getSimpleName() + ": " + code);
    }
    /** 机器人业务错误；统一由 BizException 承载，不增加异常子类。 */
    @Getter public enum Error implements Code {
        VERSION_CONFLICT("VERSION_CONFLICT"), IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT"),
        WECOM_IDENTITY_CONFLICT("IM_BOT_WECOM_IDENTITY_CONFLICT"),
        LEASE_LOST("IM_BOT_LEASE_LOST"),
        PLATFORM_AUTH_FAILED("IM_BOT_PLATFORM_AUTH_FAILED"), CONNECTION_UNAVAILABLE("IM_BOT_CONNECTION_UNAVAILABLE"),
        PLATFORM_REQUEST_FAILED("IM_BOT_PLATFORM_REQUEST_FAILED"), PLATFORM_REQUEST_TIMEOUT("IM_BOT_PLATFORM_REQUEST_TIMEOUT"),
        INBOUND_FAILED("IM_BOT_INBOUND_FAILED"), FILE_FAILED("IM_BOT_FILE_FAILED"),
        FILE_TOO_LARGE("IM_BOT_FILE_TOO_LARGE"), DOCUMENT_NOT_CONFIGURED("IM_BOT_DOCUMENT_NOT_CONFIGURED"),
        DOCUMENT_PERMISSION_DENIED("IM_BOT_DOCUMENT_PERMISSION_DENIED"), DOCUMENT_FAILED("IM_BOT_DOCUMENT_FAILED"),
        DOCUMENT_RESULT_UNKNOWN("IM_BOT_DOCUMENT_RESULT_UNKNOWN"), TOOL_NOT_CONFIGURED("IM_BOT_TOOL_NOT_CONFIGURED"),
        TOOL_PERMISSION_DENIED("IM_BOT_TOOL_PERMISSION_DENIED"), TOOL_FAILED("IM_BOT_TOOL_FAILED"),
        TOOL_RESULT_UNKNOWN("IM_BOT_TOOL_RESULT_UNKNOWN");
        /** 对调用方及后台日志使用的稳定业务错误码。 */
        private final String code;
        Error(String code){this.code=code;}
    }
    @Getter public enum Wire implements Code { TEXT("text"), POST("post"), LINK("a"), CODE_BLOCK("code_block"), FILE("file"), IMAGE("image"), POST_IMAGE("img"), AUDIO("audio"), VOICE("voice"), MIXED("mixed"), MARKDOWN("markdown"), INTERACTIVE("interactive"), STREAM("stream"), GROUP("group"), USER("user"); private final String code; Wire(String code){this.code=code;} }
    @Getter public enum Platform implements Code { WECOM("WECOM"), FEISHU("FEISHU"), DINGTALK("DINGTALK"); private final String code; Platform(String code) { this.code=code; } }
    /** 平台收发身份，真人模式只使用本人授权，不回退机器人。 */
    @Getter public enum Identity implements Code { BOT("BOT"), USER("USER"); private final String code; Identity(String code){this.code=code;} }
    /** 真人接管所需的企微消息工具。 */
    @Getter public enum UserTool implements Code { GROUPS("chat_groups_list"), MESSAGES("chat_messages_list"), FILES("message_files_get"), SEND("message_send"); private final String code; UserTool(String code){this.code=code;} }
    /** 飞书真人消息收发及续期所需的官方 OAuth 权限。 */
    @Getter public enum FeishuScope implements Code {
        OFFLINE_ACCESS("offline_access"), MESSAGE("im:message"), MESSAGE_READONLY("im:message:readonly"),
        PRIVATE_MESSAGES("im:message.p2p_msg:get_as_user"), GROUP_MESSAGES("im:message.group_msg:get_as_user"),
        SEND_AS_USER("im:message.send_as_user"), SEARCH_MESSAGES("search:message");
        /** 飞书开放平台定义的权限代码，点号与冒号须原样保留。 */
        private final String code;
        FeishuScope(String code){this.code=code;}
    }
    @Getter public enum Connection implements Code { DISABLED("DISABLED"), CONNECTING("CONNECTING"), ONLINE("ONLINE"), ERROR("ERROR"); private final String code; Connection(String code) { this.code=code; } }
    @Getter public enum Direction implements Code { INBOUND("INBOUND"), OUTBOUND("OUTBOUND"); private final String code; Direction(String code) { this.code=code; } }
    /** 平台会话类型及企微协议对应值。 */
    @Getter public enum SessionType implements Code {
        P2P("P2P",1), GROUP("GROUP",2);
        /** 工作台统一会话类型代码。 */
        private final String code;
        /** 企业微信主动发送接口的会话类型值。 */
        private final Integer wecomCode;
        SessionType(String code,Integer wecomCode) { this.code=code;this.wecomCode=wecomCode; }
    }
    @Getter public enum Content implements Code { TEXT("TEXT"), FILE("FILE"), MARKDOWN("MARKDOWN"); private final String code; Content(String code) { this.code=code; } }
    /** 平台录音及既有转写链路使用的附件扩展名，AMR和独立OPUS先转换为PCM。 */
    @Getter public enum AudioFormat implements Code {
        MP3("mp3"), WAV("wav"), M4A("m4a"), WEBM("webm"), OGG("ogg"), OPUS("opus"), AMR("amr"), RAW("raw");
        /** 录音文件扩展名及转写格式代码。 */
        private final String code;
        AudioFormat(String code){this.code=code;}
    }
    @Getter public enum Process implements Code { QUEUED("QUEUED"), RUNNING("RUNNING"), WAITING_INPUT("WAITING_INPUT"), CANCEL_REQUESTED("CANCEL_REQUESTED"), RECOVERING("RECOVERING"), SUCCEEDED("SUCCEEDED"), FAILED("FAILED"), CANCELED("CANCELED"); private final String code; Process(String code) { this.code=code; } }
    @Getter public enum Send implements Code { PENDING("PENDING"), SENDING("SENDING"), ACCEPTED("ACCEPTED"), FAILED("FAILED"), UNKNOWN("UNKNOWN"), CANCELED("CANCELED"); private final String code; Send(String code) { this.code=code; } }
    /** 上游问题事件的状态代码。 */
    @Getter public enum QuestionStatus implements Code {
        PENDING("pending"), ANSWERED("answered"), REJECTED("rejected");
        /** 原会话问题事件中的状态值。 */
        private final String code;
        QuestionStatus(String code){this.code=code;}
    }
    /** 已保存答案的提交状态，未知结果不自动重复提交。 */
    @Getter public enum QuestionSubmission implements Code {
        SENDING("SENDING"), ACCEPTED("ACCEPTED"), UNKNOWN("UNKNOWN");
        /** 当前问题答案提交状态。 */
        private final String code;
        QuestionSubmission(String code){this.code=code;}
    }
    @Getter public enum WecomCommand implements Code { SUBSCRIBE("aibot_subscribe"), PING("ping"), MESSAGE("aibot_msg_callback"), EVENT("aibot_event_callback"), REPLY("aibot_respond_msg"), SEND("aibot_send_msg"), UPLOAD_INIT("aibot_upload_media_init"), UPLOAD_CHUNK("aibot_upload_media_chunk"), UPLOAD_FINISH("aibot_upload_media_finish"); private final String code; WecomCommand(String code) { this.code=code; } }
}
