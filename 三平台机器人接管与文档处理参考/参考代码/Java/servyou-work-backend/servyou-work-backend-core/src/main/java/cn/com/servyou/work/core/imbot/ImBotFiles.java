package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Process;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.interfaces.conversation.IConversationService;
import cn.com.servyou.work.api.model.conversation.*;
import cn.com.servyou.work.api.model.document.FileDownloadDTO;
import cn.com.servyou.work.core.manager.accountcenter.IAccountCenterManager;
import cn.com.servyou.work.core.manager.storage.StorageManager;
import cn.com.servyou.work.core.audio.AudioPcmConversionService;
import cn.com.servyou.work.core.audio.PcmAudioResource;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.hutool.json.*;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 机器人文档只做平台传输适配，存储、会话归属和生成文件继续使用原有业务链路。 */
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotFiles {
    private final ImBotServiceImpl service;
    private final ImBotMessageMapper messages;
    private final ImBotSessionMapper sessions;
    private final ConversationFileMapper conversationFiles;
    private final IConversationService conversations;
    private final IAccountCenterManager accounts;
    private final StorageManager storage;
    private final TransactionTemplate tx;
    private final Environment environment;
    /** 复用已有录音转换器，将平台AMR、独立OPUS录音转为转写服务支持的PCM。 */
    private final AudioPcmConversionService audio;

    /** 工作线程尽快保存平台临时文档，不等待同一会话的上一轮模型执行完成。 */
    public void prepare(ImBotEntity bot,String node,Long version,ImBotMessageEntity message,ImBotChannel channel,BooleanSupplier live) {
        if(!live.getAsBoolean() || channel==null || !channel.online())return;
        try {
            accounts.runAsPortalUser(bot.getTenantId(),message.getUserId(),()->{
                if(!live.getAsBoolean() || !service.sendable(service.bot(bot.getId()),sessions.selectById(message.getTargetSessionId())))return;
                JSONObject context=JSONUtil.parseObj(message.getReplyContext());
                JSONObject source=JSONUtil.parseObj(AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),context.getStr("file")));
                try(ImBotFile downloaded=channel.downloadFile(message,source);
                    ImBotFile file=normalizeAudio(downloaded,live)) {
                    if(!live.getAsBoolean())return;
                    tx.executeWithoutResult(status->{
                        ReqConversationFileUploadDTO request=new ReqConversationFileUploadDTO();request.setSessionId(message.getSessionId());request.setUploadType(UploadTypeEnum.DIALOG.getCode());
                        ConversationFileUploadDTO saved=conversations.uploadFile(request,file);
                        // 平台下载密钥保存成功后即移除；持久化文件和入站检查点在同一事务提交。
                        context.remove("file");
                        if(messages.attachFile(message.getId(),saved.getFileId(),file.getOriginalFilename(),context.toString(),node,version)!=1)
                            throw new BizException(Error.LEASE_LOST.getCode(),"文档消息归属或授权已改变");
                    });
                } catch(RuntimeException e){throw e;}
                catch(Exception e){throw new BizException(Error.FILE_FAILED.getCode(),"平台文档读取失败",e);}
            });
        } catch(Exception e) {
            log.warn("机器人输入文档保存失败 botKey={}, messageId={}, sessionId={}\n{}",bot.getId(),message.getId(),message.getSessionId(),safeStackTrace(e));
            if(live.getAsBoolean()) {
                ImBotMessageEntity current=messages.selectById(message.getId());
                if(current!=null && current.getFileId()==null && Process.QUEUED.getCode().equals(current.getProcessState())) {
                    current.setProcessState(Process.FAILED.getCode());
                    boolean user=ImBotTakeoverConfig.user(current);current.setSendState(user?Send.FAILED.getCode():Send.PENDING.getCode());
                    current.setReplyContent(user?null:"附件未能保存，可能已过下载有效期、缺少平台权限或超过100MB，请重新发送。");
                    current.setLastError("文档下载或保存失败，请结合消息标识检查后台异常日志");messages.progress(current,node,version);
                }
            }
        }
    }

    /** 默认转写服务不直接接收AMR或独立OPUS；使用已有转换与超时限制，保留其他文件原始内容。 */
    ImBotFile normalizeAudio(ImBotFile file,BooleanSupplier live)throws Exception {
        String extension=org.apache.commons.io.FilenameUtils.getExtension(file.getOriginalFilename());
        if(!AudioFormat.AMR.getCode().equalsIgnoreCase(extension) && !AudioFormat.OPUS.getCode().equalsIgnoreCase(extension))return file;
        try(InputStream input=file.getInputStream();
            PcmAudioResource pcm=audio.convertLocal(input,file.getOriginalFilename(),()->!live.getAsBoolean());
            InputStream converted=java.nio.file.Files.newInputStream(pcm.getPcmFile())) {
            return ImBotFile.copyOf(converted,org.apache.commons.io.FilenameUtils.removeExtension(file.getOriginalFilename())+"."+AudioFormat.RAW.getCode(),
                    "application/octet-stream",ImBotFile.INPUT_LIMIT);
        }
    }

    /** 本轮终态及其成果发送记录原子提交，避免终态已落库但成果未进入原发送链路。 */
    public void complete(ImBotMessageEntity source,String node,Long version) {
        tx.executeWithoutResult(status->{
            int updated=Process.CANCELED.getCode().equals(source.getProcessState())?messages.progress(source,node,version):messages.publish(source,node,version);
            if(updated!=1)return;
            ImBotMessageEntity current=messages.selectById(source.getId());
            // 真人消息通道当前只承诺正文回复，不能将成果文件误送成机器人身份或堵塞后续输入。
            if(ImBotTakeoverConfig.user(current))return;
            if(!Process.SUCCEEDED.getCode().equals(current.getProcessState()) || current.getDbMessageId()==null)return;
            for(ConversationFileEntity link:conversationFiles.selectList(ConversationFileQueries.owned(current.getTenantId())
                    .eq(ConversationFileEntity::getCreateUserId,current.getUserId())
                    .eq(ConversationFileEntity::getSessionId,current.getSessionId()).eq(ConversationFileEntity::getMessageId,current.getDbMessageId())
                    .eq(ConversationFileEntity::getFileUsage,FileUsageTypeEnum.RESULT.getCode()).orderByAsc(ConversationFileEntity::getId))) {
                ImBotMessageEntity result=new ImBotMessageEntity();result.setId(IdWorker.getId());result.setFileId(link.getFileId());result.setContentType(Content.FILE.getCode());
                result.setContent("本轮成果文件");result.setRequestId("im-result-"+current.getId()+"-"+link.getFileId());
                result.setRequestHash(ImBotServiceImpl.hash(result.getRequestId()));result.setProviderUuid(UUID.randomUUID().toString());
                JSONObject context=JSONUtil.parseObj(org.apache.commons.lang3.StringUtils.defaultIfBlank(current.getReplyContext(),"{}"));context.remove("file");result.setReplyContext(context.toString());
                messages.acceptResult(result,current.getId(),node,version);
            }
        });
    }

    /** 发送前重新验证绑定用户的会话文件归属，限定本轮消息，不读取整个会话的历史附件。 */
    public CompletionStage<ImBotChannel.Receipt> send(ImBotChannel channel,ImBotMessageEntity message,ImBotSessionEntity target) {
        AtomicReference<CompletionStage<ImBotChannel.Receipt>> result=new AtomicReference<>();
        try {
            accounts.runAsPortalUser(message.getTenantId(),message.getUserId(),()->{
                FileDownloadDTO file;
                // 自动成果同样是OUTBOUND，通过原轮次关联区分，保留严格的本轮RESULT校验。
                if(message.getDbMessageId()==null) {
                    String sourceId=JSONUtil.parseObj(message.getReplyContext()).getStr("sourceConversationFileId");
                    if(sourceId==null || !sourceId.matches("[1-9][0-9]{0,18}"))throw new BizException(Error.FILE_FAILED.getCode(),"附件来源记录无效");
                    file=conversations.getConversationDownloadFile(message.getSessionId(),Long.valueOf(sourceId));
                    if(!Objects.equals(file.getFileId(),message.getFileId()))throw new BizException(Error.FILE_FAILED.getCode(),"附件来源已变化");
                } else {
                    ConversationFileEntity link=conversationFiles.selectOne(ConversationFileQueries.owned(message.getTenantId())
                            .eq(ConversationFileEntity::getCreateUserId,message.getUserId())
                            .eq(ConversationFileEntity::getSessionId,message.getSessionId()).eq(ConversationFileEntity::getMessageId,message.getDbMessageId())
                            .eq(ConversationFileEntity::getFileId,message.getFileId()).eq(ConversationFileEntity::getFileUsage,FileUsageTypeEnum.RESULT.getCode()).last("LIMIT 1"));
                    if(link==null)throw new BizException(Error.FILE_FAILED.getCode(),"本轮成果已变更或不再可用，请在工作台查看");
                    file=conversations.getConversationDownloadFile(message.getSessionId(),link.getId());
                }
                long limit=Platform.WECOM.getCode().equals(service.bot(message.getBotId()).getPlatform())?20L*1024*1024:30L*1024*1024;
                if(file.getFileSize()==null || file.getFileSize()>limit)throw new BizException(Error.FILE_TOO_LARGE.getCode(),"成果超过平台附件大小限制，请在工作台下载");
                try(InputStream input=storage.getObject(file.getBucketName(),file.getObjectKey());
                    ImBotFile local=ImBotFile.copyOf(input,file.getFileName(),"application/octet-stream",limit)) {
                    result.set(channel.sendFile(message,target,local.path(),local.getOriginalFilename()));
                }catch(RuntimeException e){throw e;}
                catch(Exception e){throw new BizException(Error.FILE_FAILED.getCode(),"成果文件上传失败",e);}
            });
            return result.get();
        } catch(Exception e) {
            log.warn("机器人成果文件发送准备失败 botKey={}, messageId={}, fileId={}, sessionId={}\n{}",message.getBotId(),message.getId(),message.getFileId(),message.getSessionId(),safeStackTrace(e));
            return CompletableFuture.completedFuture(new ImBotChannel.Receipt(Send.FAILED.getCode(),null,
                    e instanceof BizException?((BizException)e).getMessage():"成果文件发送失败，请在工作台下载并检查后台日志"));
        }
    }
}
