package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.dao.entity.*;
import lombok.Data;
import lombok.ToString;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import cn.hutool.json.JSONObject;
import cn.com.servyou.work.api.enums.ImBotEnums;
import java.nio.file.Path;

/** 只封装本次需要的接收、连接状态和一次发送，不提供任意平台 API 代理。 */
public interface ImBotChannel extends AutoCloseable {
    /** 当前连接是否完成认证且可以发送。 */
    boolean online();
    /** 向已校验目标发送一次，结果只代表平台受理状态。 */
    CompletionStage<Receipt> send(ImBotMessageEntity message,ImBotSessionEntity target);
    /** 工作线程下载当前消息中的文档，返回的临时文件由调用方关闭。 */
    default ImBotFile downloadFile(ImBotMessageEntity message, JSONObject source) throws Exception {
        throw new cn.com.servyou.work.api.exception.BizException(ImBotEnums.Error.FILE_FAILED.getCode(),"平台未实现文档下载");
    }
    /** 工作线程上传成果并发送文件，I/O 回调不执行文件读写。 */
    default CompletionStage<Receipt> sendFile(ImBotMessageEntity message,ImBotSessionEntity target,Path file,String name) throws Exception {
        throw new cn.com.servyou.work.api.exception.BizException(ImBotEnums.Error.FILE_FAILED.getCode(),"平台未实现文档发送");
    }
    /** 关闭连接并释放此机器人占用的资源。 */
    void close();
    /** 平台文本或文档事件，仅用于短事务接收。 */
    @Data class Inbound {
        /** 当前机器人下的外部发送用户标识。 */
        private String externalUserId;
        /** 平台外部会话标识。 */
        private String chatId;
        /** 用于入站去重的平台消息标识。 */
        private String messageId;
        /** 单聊或群聊的领域类型代码。 */
        private String sessionType;
        /** 入站内容类型，支持文本和文档。 */
        private String contentType=ImBotEnums.Content.TEXT.getCode();
        /** 用户发送的文本，禁止写入普通日志。 */
        @ToString.Exclude private String content;
        /** 平台回复需要的连接及请求关联信息。 */
        @ToString.Exclude private String context;
    }
    /** 单次发送的平台回执。 */
    @Data class Receipt {
        /** 平台发送状态代码。 */
        private final String state;
        /** 平台返回的消息标识，没有返回时为空。 */
        private final String providerMessageId;
        /** 脱敏后的发送错误说明。 */
        private final String error;
    }
    /** 平台客户端创建入口，复用节点级传输资源。 */
    interface Factory {
        /** 使用后端配置建立一个机器人连接，回调仅提交入站业务。 */
        ImBotChannel connect(ImBotEntity bot,String secret,Consumer<Inbound> receive) throws Exception;
    }
}
