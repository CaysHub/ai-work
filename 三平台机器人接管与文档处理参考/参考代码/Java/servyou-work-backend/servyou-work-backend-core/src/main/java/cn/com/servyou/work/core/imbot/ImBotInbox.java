package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.hutool.json.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;
import java.util.*;

/** 回调只做短事务入库，事务提交后上层才确认；绑定原文不进入消息历史。 */
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotInbox {
    private final ImBotServiceImpl service;
    private final ImBotMapper bots;
    private final ImBotUserMapper users;
    private final ImBotSessionMapper sessions;
    private final ImBotMessageMapper messages;
    private final TransactionTemplate transactionTemplate;
    private final Environment environment;

    public void accept(Long key,String node,Long version,ImBotChannel.Inbound event) {
        accept(key,node,version,event,false);
    }
    /** 兼容原单人配置的短时绑定入口，只消费私聊绑定码。 */
    void accept(Long key,String node,Long version,ImBotChannel.Inbound event,boolean bindingOnly) {
        ImBotServiceImpl.text(event.getExternalUserId(),"外部用户",256);
        ImBotServiceImpl.text(event.getChatId(),"外部会话",256);
        ImBotServiceImpl.text(event.getMessageId(),"平台消息",256);
        if(StringUtils.isBlank(event.getContent()) || event.getContent().length()>20000) return;
        if(!Content.TEXT.getCode().equals(event.getContentType()) && !Content.FILE.getCode().equals(event.getContentType()))return;
        TransactionTemplate tx=new TransactionTemplate(transactionTemplate.getTransactionManager()); tx.setTimeout(2);
        tx.executeWithoutResult(status->{
            ImBotEntity b=service.lockBot(key);
            // 已切换真人模式的账号不能再接收旧机器人连接的回调。
            if(b!=null && ImBotTakeoverConfig.user(b)!=bindingOnly)return;
            if(b==null || !ImBotServiceImpl.yes(b.getEnabled()) || !node.equals(b.getOwnerNode()) || !version.equals(b.getOwnerVersion())
                    || b.getLeaseUntil()==null || !b.getLeaseUntil().isAfter(bots.databaseNow())) throw new BizException(Error.LEASE_LOST.getCode(),"Bot归属已失效");
            String inboundKey=ImBotServiceImpl.hash(event.getMessageId());
            if(messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,key).eq(ImBotMessageEntity::getInboundKey,inboundKey))>0) return;
            String externalKey=ImBotServiceImpl.hash(event.getExternalUserId());
            ImBotUserEntity u=users.selectOne(new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,key).eq(ImBotUserEntity::getExternalUserKey,externalKey));
            boolean binding=Content.TEXT.getCode().equals(event.getContentType()) && SessionType.P2P.getCode().equals(event.getSessionType()) && event.getContent().matches("绑定 [A-F0-9]{16}");
            if(bindingOnly && !binding)return;
            if(binding) {
                String code=event.getContent().substring(3);
                ImBotUserEntity candidate=users.selectOne(new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,key)
                        .eq(ImBotUserEntity::getAuthorized,ImBotServiceImpl.flag(true)).eq(ImBotUserEntity::getBindingCodeHash,ImBotServiceImpl.hash(code))
                        .gt(ImBotUserEntity::getBindingCodeExpiresAt,bots.databaseNow()));
                if(candidate==null || u!=null && !u.getId().equals(candidate.getId())) return;
                int consumed=users.update(null,new LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,candidate.getId())
                        .eq(ImBotUserEntity::getAuthorized,ImBotServiceImpl.flag(true)).eq(ImBotUserEntity::getBindingCodeHash,ImBotServiceImpl.hash(code))
                        .isNull(ImBotUserEntity::getExternalUserKey).gt(ImBotUserEntity::getBindingCodeExpiresAt,bots.databaseNow())
                        .set(ImBotUserEntity::getExternalUserId,event.getExternalUserId()).set(ImBotUserEntity::getExternalUserKey,externalKey)
                        .set(ImBotUserEntity::getBoundAt,bots.databaseNow()).set(ImBotUserEntity::getBindingCodeHash,null).set(ImBotUserEntity::getBindingCodeExpiresAt,null));
                if(consumed!=1 || bindingOnly) return;
                candidate.setExternalUserId(event.getExternalUserId());candidate.setExternalUserKey(externalKey);u=candidate;
            }
            if(u==null || !ImBotServiceImpl.yes(u.getAuthorized())) {
                log.warn("机器人入站用户未授权或未绑定 botKey={}, platform={}, externalAppId={}, node={}, ownerVersion={}, externalUserId={}, externalMessageId={}",
                        key,b.getPlatform(),b.getExternalAppId(),node,version,event.getExternalUserId(),event.getMessageId());
                return;
            }
            ImBotSessionEntity target=sessions.selectOne(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,key)
                    .eq(ImBotSessionEntity::getUserId,u.getUserId()).eq(ImBotSessionEntity::getChatKey,ImBotServiceImpl.hash(event.getChatId())));
            if(target==null) {
                target=new ImBotSessionEntity();target.setBotId(key);target.setTenantId(b.getTenantId());target.setUserId(u.getUserId());
                target.setExternalChatId(event.getChatId());target.setChatKey(ImBotServiceImpl.hash(event.getChatId()));target.setSessionType(event.getSessionType());
                target.setName(SessionType.P2P.getCode().equals(event.getSessionType())?"机器人单聊":"群聊 "+StringUtils.right(event.getChatId(),8));sessions.insert(target);
            } else sessions.update(null,new LambdaUpdateWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getId,target.getId()).set(ImBotSessionEntity::getLastInteractionAt,bots.databaseNow()));
            // 已绑定用户的未知群只保存发现元数据；普通消息不进入模型和消息队列。
            if(!binding && (!ImBotServiceImpl.yes(b.getAutoProcessEnabled()) || !service.sendable(b,target))) return;
            if(target.getSessionId()==null && !binding) {
                String sessionId=UUID.randomUUID().toString();
                sessions.update(null,new LambdaUpdateWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getId,target.getId()).isNull(ImBotSessionEntity::getSessionId).set(ImBotSessionEntity::getSessionId,sessionId));target.setSessionId(sessionId);
            }
            ImBotMessageEntity m=new ImBotMessageEntity();m.setId(IdWorker.getId());m.setBotId(key);m.setTenantId(b.getTenantId());m.setUserId(u.getUserId());m.setTargetSessionId(target.getId());
            m.setDirection(Direction.INBOUND.getCode());m.setContentType(event.getContentType());m.setContent(binding?"[绑定控制消息]":event.getContent());
            String context=event.getContext();boolean tooManyFiles=false;
            if(Content.FILE.getCode().equals(event.getContentType())) {
                JSONObject metadata=JSONUtil.parseObj(context);JSONObject source=metadata.getJSONObject("file");
                tooManyFiles=metadata.getInt("fileCount",1)>1;
                if(source==null || source.toString().length()>20000)throw new BizException(Error.FILE_FAILED.getCode(),"入站文档元数据无效");
                metadata.set("file",AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(environment),source.toString()));context=metadata.toString();
            }
            m.setInboundKey(inboundKey);m.setExternalMessageId(event.getMessageId());m.setReplyContext(context);m.setProviderUuid(UUID.randomUUID().toString());
            m.setRequestId("im-"+m.getId());m.setProcessState(binding?ImBotEnums.Process.SUCCEEDED.getCode():tooManyFiles?ImBotEnums.Process.FAILED.getCode():ImBotEnums.Process.QUEUED.getCode());m.setSessionId(target.getSessionId());
            if(messages.acceptInbound(m,node,version)!=1) throw new BizException(Error.LEASE_LOST.getCode(),"Bot归属已失效");
            if(tooManyFiles) {
                log.warn("飞书单条消息附件数量超出当前接收范围 botKey={}, externalUserId={}, externalMessageId={}",key,event.getExternalUserId(),event.getMessageId());
                m.setReplyContent("这条消息包含多个附件，请每次发送一个附件，可以同时附上文字处理要求。");
                m.setSendState(Send.PENDING.getCode());messages.progress(m,node,version);
            }
            if(bindingOnly && !binding)return;
            if(binding) {m.setReplyContent("绑定成功，可以开始对话。自动处理开关由管理员配置。");m.setSendState(Send.PENDING.getCode());messages.progress(m,node,version);}
        });
    }
}
