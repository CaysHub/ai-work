package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Process;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.hutool.json.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 当前Bot租约持有者的真人会话拉取、去重与回复前复查，等待状态仍保存在原四表。 */
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotTakeover {
    private final ImBotMapper bots;
    private final ImBotSessionMapper sessions;
    private final ImBotMessageMapper messages;
    private final ImBotServiceImpl service;
    private final ImBotUserGateway gateway;
    private final ImBotTakeoverServiceImpl authorization;
    private final Environment environment;
    private final TransactionTemplate tx;

    ImBotChannel connect(ImBotEntity b,String node,Long version,BooleanSupplier live){return new UserChannel(b,node,version,live);}
    void poll(ImBotChannel channel){if(channel instanceof UserChannel)((UserChannel)channel).poll();}
    /** 配置代次变化后，旧运行不再恢复或发送到新身份。 */
    boolean matchesCurrent(ImBotEntity b,ImBotMessageEntity row){return ImBotTakeoverConfig.matches(b,row,environment);}
    private ImBotEntity current(ImBotEntity expected,String node,Long version){
        ImBotEntity b=service.bot(expected.getId());
        if(!ImBotTakeoverConfig.user(b) || !ImBotServiceImpl.yes(b.getEnabled()) || !ImBotServiceImpl.yes(b.getAutoProcessEnabled())
                || !Objects.equals(expected.getConfigVersion(),b.getConfigVersion()) || !Objects.equals(node,b.getOwnerNode())
                || !Objects.equals(version,b.getOwnerVersion()) || b.getLeaseUntil()==null || !b.getLeaseUntil().isAfter(bots.databaseNow()))throw lost();
        JSONObject c=ImBotTakeoverConfig.read(b,environment);ImBotUserEntity owner=service.user(b.getId(),c.getStr("ownerUserId"));
        if(owner==null || !ImBotServiceImpl.yes(owner.getAuthorized()) || !Objects.equals(owner.getExternalUserId(),c.getStr("externalUserId")))
            throw BizException.noPermission("接管账号授权绑定已失效");
        ImBotTakeoverConfig.messageUserId(b,c);return b;
    }
    private void checkpoint(ImBotEntity b,String node,Long version,JSONObject c){
        String cipher=ImBotTakeoverConfig.encrypt(c,environment);
        if(bots.takeoverCheckpoint(b,node,version,b.getTakeoverConfigCipher(),cipher)!=1)throw lost();b.setTakeoverConfigCipher(cipher);
    }
    /** 只在单个拉取批次内续期；先持久标记，崩溃后不重复消费一次性refresh_token。 */
    private JSONObject credentials(ImBotEntity b,String node,Long version)throws Exception {
        JSONObject c=ImBotTakeoverConfig.read(b,environment);
        boolean dingTalk=Platform.DINGTALK.getCode().equals(b.getPlatform());
        if(!dingTalk && !Platform.FEISHU.getCode().equals(b.getPlatform()))return c;
        if(dingTalk && !DingTalkUserAuthorization.ready(c))
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"钉钉本人授权已失效，请重新授权");
        if(c.getBool("refreshing",false))throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"上次用户令牌续期结果未知，请重新授权");
        if(c.getLong("accessExpiresAt",0L)>System.currentTimeMillis()+120000)return c;
        if(!dingTalk && (c.getLong("refreshExpiresAt",0L)<=System.currentTimeMillis() || StringUtils.isBlank(c.getStr("refreshToken"))))
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书本人授权已过期，请重新授权");
        c.set("refreshing",true);checkpoint(b,node,version,c);
        if(dingTalk){
            DingTalkUserAuthorization.refresh(c);
        }else{
            JSONObject token=authorization.exchange(b,JSONUtil.createObj().set("grant_type","refresh_token").set("refresh_token",c.getStr("refreshToken")));
            ImBotTakeoverServiceImpl.saveToken(c,token);
        }
        checkpoint(b,node,version,c);return c;
    }
    /** 处理前、发送前均调用；未知或不完整历史不会放行自动发送。 */
    boolean unanswered(ImBotEntity expected,String node,Long version,ImBotMessageEntity row)throws Exception {
        ImBotEntity b=current(expected,node,version);
        ImBotMessageEntity persisted=messages.selectById(row.getId());
        if(persisted==null || Process.CANCELED.getCode().equals(persisted.getProcessState()) || Send.ACCEPTED.getCode().equals(persisted.getSendState())
                && ImBotExecution.terminal(persisted.getProcessState()))return false;
        if(!ImBotTakeoverConfig.matches(b,row,environment)){messages.skipReplied(row.getId(),node,version);return false;}
        ImBotSessionEntity target=sessions.selectById(row.getTargetSessionId());
        if(!service.sendable(b,target)){messages.skipReplied(row.getId(),node,version);return false;}
        JSONObject c=ImBotTakeoverConfig.read(b,environment),source=ImBotTakeoverConfig.context(row);
        if (!groupReplyAllowed(b,target,source)) {
            messages.skipReplied(row.getId(),node,version);
            return false;
        }
        String self=ImBotTakeoverConfig.messageUserId(b,c);
        if(successfulReplyAt(target)>=source.getLong("sourceTime",Long.MAX_VALUE)){messages.skipReplied(row.getId(),node,version);return false;}
        Set<String> questions=questionTexts(target);
        long start=source.getLong("sourceTime",0L)/1000-1,end=System.currentTimeMillis()/1000+1;
        String cursor=null;
        for(int i=0;i<4;i++){
            ImBotUserGateway.Page page=gateway.history(b,c,target.getExternalChatId(),start,end,cursor);
            for(Object item:page.getItems()){
                JSONObject m=JSONUtil.parseObj(item);
                if(self.equals(m.getStr("sender")) && after(m,source,secondPrecision(b))
                        && !questions.contains(m.getStr("text"))){
                    messages.skipReplied(row.getId(),node,version);
                    log.info("真人本人已回复，跳过自动回复 botKey={}, chatId={}, externalUserId={}, messageUserId={}, messageId={}",b.getId(),target.getExternalChatId(),c.getStr("externalUserId"),self,row.getId());return false;
                }
            }
            cursor=page.getNext();
            if (cursor==null) {
                current(b,node,version);
                if (groupReplyAllowed(b,target,source)) return true;
                messages.skipReplied(row.getId(),node,version);
                return false;
            }
        }
        throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"发送前历史分页超出本批预算，已停止自动回复，请人工检查");
    }
    static boolean after(JSONObject message,JSONObject source,boolean secondPrecision){
        long time=message.getLong("time",0L),sourceTime=source.getLong("sourceTime",Long.MAX_VALUE);
        return time>sourceTime || time==sourceTime && (secondPrecision || message.getLong("position",0L)>source.getLong("sourcePosition",0L));
    }
    private static boolean secondPrecision(ImBotEntity bot) {
        return Platform.WECOM.getCode().equals(bot.getPlatform()) || Platform.DINGTALK.getCode().equals(bot.getPlatform());
    }
    /** 其他成员加入共享群后，未被点名且仍在排队的旧消息也要停止自动回复。 */
    private boolean groupReplyAllowed(ImBotEntity bot, ImBotSessionEntity target, JSONObject source) {
        if (!SessionType.GROUP.getCode().equals(target.getSessionType()) || source.getBool("groupMentioned",false)) return true;
        for (ImBotEntity peer : service.family(bot)) {
            if (sharedGroupPeer(bot,peer) && ImBotTakeoverConfig.list(ImBotTakeoverConfig.read(peer,environment),"groupChatIds")
                    .contains(target.getExternalChatId())) return false;
        }
        return true;
    }

    private static boolean sharedGroupPeer(ImBotEntity bot, ImBotEntity peer) {
        return !peer.getId().equals(bot.getId()) && ImBotTakeoverConfig.user(peer);
    }
    /** 平台历史尚未同步时也尊重本系统已成功送出的终态回复。 */
    private long successfulReplyAt(ImBotSessionEntity target){
        List<ImBotMessageEntity> accepted=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>()
                .eq(ImBotMessageEntity::getTargetSessionId,target.getId()).eq(ImBotMessageEntity::getProcessState,Process.SUCCEEDED.getCode())
                .eq(ImBotMessageEntity::getSendState,Send.ACCEPTED.getCode())
                .orderByDesc(ImBotMessageEntity::getUpdateTime).last("LIMIT 1"));
        // 只覆盖已经处理的输入水位，不能把模型执行期间新到达的“总结一下”等指令一并吞掉。
        return accepted.isEmpty()?0:ImBotTakeoverConfig.context(accepted.get(0)).getLong("sourceTime",0L);
    }
    /** 系统送出的追问和终态回复不能误判为本人手动回复，后续新输入仍须处理。 */
    private Set<String> questionTexts(ImBotSessionEntity target){
        Set<String> texts=new HashSet<>();
        List<ImBotMessageEntity> waiting=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>()
                .eq(ImBotMessageEntity::getTargetSessionId,target.getId()).isNotNull(ImBotMessageEntity::getReplyContext)
                .orderByDesc(ImBotMessageEntity::getId).last("LIMIT 100"));
        for(ImBotMessageEntity row:waiting){JSONArray sent=ImBotTakeoverConfig.context(row).getJSONArray("sentQuestions");
            if(ImBotTakeoverConfig.user(row)) {
                if(sent!=null)texts.addAll(sent.toList(String.class));
                if((Send.ACCEPTED.getCode().equals(row.getSendState()) || Send.SENDING.getCode().equals(row.getSendState()))
                        && StringUtils.isNotBlank(row.getReplyContent()))texts.add(row.getReplyContent());
            }
        }
        return texts;
    }
    private ImBotSessionEntity target(ImBotEntity b,JSONObject c,String chat,String type,String name,String node,Long version){
        ImBotServiceImpl.text(chat,"chatId",256);
        return tx.execute(status->{
            ImBotEntity locked=service.lockBot(b.getId());current(locked,node,version);
            if(!Objects.equals(b.getConfigVersion(),locked.getConfigVersion()))throw lost();
            String key=ImBotServiceImpl.hash(Identity.USER.getCode()+":"+c.getStr("generation")+":"+chat);
            ImBotSessionEntity s=sessions.selectOne(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,b.getId()).eq(ImBotSessionEntity::getUserId,c.getStr("ownerUserId")).eq(ImBotSessionEntity::getChatKey,key));
            if(s==null){s=new ImBotSessionEntity();s.setBotId(b.getId());s.setTenantId(b.getTenantId());s.setUserId(c.getStr("ownerUserId"));
                s.setExternalChatId(chat);s.setChatKey(key);s.setSessionType(type);s.setName(StringUtils.left(StringUtils.defaultIfBlank(name,"真人会话 "+StringUtils.right(chat,8)),128));
                s.setSessionId(UUID.randomUUID().toString());sessions.insert(s);}
            return s;
        });
    }
    private void discover(ImBotEntity b,JSONObject c,String node,Long version)throws Exception {
        if(!c.getBool("discoverEnabled",false))return;
        JSONObject state=c.getJSONObject("discovery");if(state==null){state=new JSONObject();c.set("discovery",state);}
        long now=System.currentTimeMillis()/1000-2;
        if(!state.containsKey("end"))state.set("start",Math.max(c.getLong("startedAt")/1000-60,state.getLong("until",c.getLong("startedAt")/1000)-120)).set("end",now);
        long start=state.getLong("start"),end=state.getLong("end");if(end<=start)return;
        ImBotUserGateway.Page page=gateway.discover(b,c,start,end,state.getStr("cursor"));
        Set<String> found=new HashSet<>();for(Object item:page.getItems()){JSONObject chat=JSONUtil.parseObj(item);
            if(found.add(chat.getStr("chatId")))target(b,c,chat.getStr("chatId"),chat.getStr("type"),chat.getStr("name"),node,version);}
        if(page.getNext()!=null)state.set("cursor",page.getNext());else{state.set("until",end);state.remove("start");state.remove("end");state.remove("cursor");}
    }
    private void read(ImBotEntity b,JSONObject c,ImBotSessionEntity s,String node,Long version)throws Exception {
        if(!service.sendable(b,s))return;
        JSONObject state=JSONUtil.parseObj(StringUtils.defaultIfBlank(s.getTakeoverState(),"{}"));long now=System.currentTimeMillis()/1000-2;
        if(!state.containsKey("end")){
            long initial=c.getLong("startedAt")/1000-600;
            state.set("start",state.containsKey("until")?Math.max(initial,state.getLong("until")-30):initial).set("end",now).set("messages",new JSONArray());
        }
        long start=state.getLong("start"),end=state.getLong("end");if(end<=start)return;
        if(now-start>6*86400)throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"接管积压超过六天，请人工检查后重新启用，未跳过旧水位");
        ImBotUserGateway.Page page=gateway.history(b,c,s.getExternalChatId(),start,end,state.getStr("cursor"));
        JSONArray accumulated=state.getJSONArray("messages");accumulated.addAll(page.getItems());
        if(accumulated.size()>1000)throw new BizException(Error.PLATFORM_REQUEST_FAILED.getCode(),"单个读取窗口超过1000条，已暂停接管，请人工缩小范围");
        if(page.getNext()!=null)state.set("cursor",page.getNext());else{
            JSONArray combined=combineHistory(s.getExternalChatId(),state.getJSONArray("history"),accumulated);
            ingest(b,c,s,combined,node,version);
            state.set("history",new JSONArray(combined.subList(Math.max(0,combined.size()-30),combined.size())));
            state.set("until",end);state.remove("messages");state.remove("start");state.remove("end");state.remove("cursor");
        }
        s.setTakeoverState(state.toString());if(sessions.takeoverCheckpoint(s,b,node,version)!=1)throw lost();
    }
    /** 完整窗口排序后分配同秒重复序号，避免简单正文哈希吞掉重复消息。 */
    static List<JSONObject> identify(String chat,JSONArray input){
        List<JSONObject> rows=new ArrayList<>();for(Object value:input)rows.add(JSONUtil.parseObj(JSONUtil.toJsonStr(value)));
        rows.sort(Comparator.comparingLong((JSONObject m)->m.getLong("time",0L)).thenComparingLong(m->m.getLong("position",0L)));
        Map<String,Integer> occurrence=new HashMap<>();
        for(JSONObject row:rows)if(StringUtils.isBlank(row.getStr("id"))){
            String key=ImBotServiceImpl.hash(chat+":"+row.getStr("sender")+":"+row.getLong("time")+":"+row.getStr("type")+":"+row.getStr("text")
                    +(row.getJSONObject("file")==null?"":":"+row.getJSONObject("file").toString()));
            int count=occurrence.merge(key,1,Integer::sum);row.set("id",key+":"+count);
        }return rows;
    }
    /** 保留最近聊天上下文，重叠窗口按平台ID或同秒序号合并，不重复累积正文。 */
    static JSONArray combineHistory(String chat,JSONArray previous,JSONArray incoming){
        Map<String,JSONObject> unique=new LinkedHashMap<>();
        if(previous!=null)for(Object value:previous){JSONObject row=JSONUtil.parseObj(value);unique.put(row.getStr("id"),row);}
        for(JSONObject row:identify(chat,incoming))unique.put(row.getStr("id"),row);
        List<JSONObject> ordered=new ArrayList<>(unique.values());ordered.sort(Comparator.comparingLong((JSONObject m)->m.getLong("time",0L)).thenComparingLong(m->m.getLong("position",0L)));
        return new JSONArray(ordered);
    }
    private void ingest(ImBotEntity b,JSONObject c,ImBotSessionEntity s,JSONArray input,String node,Long version){
        List<JSONObject> rows=identify(s.getExternalChatId(),input);String self=ImBotTakeoverConfig.messageUserId(b,c);
        if(rows.isEmpty())return;
        // 核对同一应用内其他接管人的系统回复，人工发言正常处理。
        Map<String,Long> peerIds = new HashMap<>();
        boolean group=SessionType.GROUP.getCode().equals(s.getSessionType());
        String ownName=c.getStr("groupMentionName");
        List<String> names=new ArrayList<>();
        names.add(ownName);
        boolean shared=false;
        for (ImBotEntity peer : service.family(b)) {
            if (!sharedGroupPeer(b,peer)) continue;
            JSONObject profile=ImBotTakeoverConfig.read(peer,environment);
            String sender=profile.getStr("messageUserId",profile.getStr("externalUserId"));
            if(StringUtils.isNotBlank(sender)) peerIds.put(sender,peer.getId());
            if (group && ImBotTakeoverConfig.list(profile,"groupChatIds").contains(s.getExternalChatId())) {
                shared=true;
                names.add(profile.getStr("groupMentionName"));
            }
        }
        final boolean sharedGroup=shared;
        Pattern mentionNames=group?ImBotGroupMentions.names(names):null;
        // 每次最多落50条，避免大窗口持有Bot行锁阻塞租约心跳；中途退出依靠原入站唯一键重读。
        for(int offset=0;offset<rows.size();offset+=50){final int begin=offset,end=Math.min(rows.size(),offset+50);
        tx.executeWithoutResult(status->{
            ImBotEntity locked=service.lockBot(b.getId());if(!Objects.equals(b.getConfigVersion(),locked.getConfigVersion()))throw lost();current(b,node,version);
            Set<String> questions=questionTexts(s);long acceptedAt=successfulReplyAt(s);JSONObject lastSelf=null;
            for(JSONObject row:rows){
                if(self.equals(row.getStr("sender")) && !questions.contains(row.getStr("text")))lastSelf=row;
            }
            if(lastSelf!=null){
                // 同会话本人后发即覆盖此前输入；同秒无法证明先后时采用保守跳过。
                List<ImBotMessageEntity> pending=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getTargetSessionId,s.getId())
                        .and(q->q.isNull(ImBotMessageEntity::getSendState).or().in(ImBotMessageEntity::getSendState,Send.PENDING.getCode())));
                for(ImBotMessageEntity m:pending)if(after(lastSelf,ImBotTakeoverConfig.context(m),secondPrecision(b)))messages.skipReplied(m.getId(),node,version);
            }
            for(int i=begin;i<end;i++){
                JSONObject row=rows.get(i);long time=row.getLong("time",0L);
                JSONObject media=row.getJSONObject("file");boolean file=media!=null;
                if(time<c.getLong("startedAt",Long.MAX_VALUE) || self.equals(row.getStr("sender")) || row.getBool("aiSent",false)
                        || peerReply(b,s,self,peerIds.get(row.getStr("sender")),row) || !Wire.USER.getCode().equals(row.getStr("senderType"))
                        || !file && StringUtils.isBlank(row.getStr("text")))continue;
                boolean mentioned=group && ImBotGroupMentions.addressed(row,self,ownName,mentionNames);
                if (sharedGroup && !mentioned) continue;
                JSONObject context=JSONUtil.createObj().set("identityMode",Identity.USER.getCode()).set("generation",c.getStr("generation"))
                        .set("externalUserId",row.getStr("sender")).set("sourceTime",time).set("sourcePosition",row.getLong("position",0L));
                if (group) context.set("groupMentioned",mentioned);
                if(lastSelf!=null && after(lastSelf,context,secondPrecision(b)) || acceptedAt>=time)continue;
                String inbound=ImBotServiceImpl.hash(Identity.USER.getCode()+":"+c.getStr("generation")+":"+s.getExternalChatId()+":"+row.getStr("id"));
                if(messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getInboundKey,inbound))>0)continue;
                JSONArray history=new JSONArray();for(int j=Math.max(0,i-30);j<i;j++){
                    JSONObject previous=rows.get(j);history.add(JSONUtil.createObj().set("sender",previous.getStr("sender")).set("text",StringUtils.left(previous.getStr("text"),500)));
                }context.set("history",history);
                if(file)context.set("file",cn.com.servyou.work.core.utils.AesCryptoUtil.encrypt(
                        cn.com.servyou.work.core.utils.AesCryptoUtil.resolveKey(environment),media.toString())).set("prompt",row.getStr("text"));
                ImBotMessageEntity m=new ImBotMessageEntity();m.setId(IdWorker.getId());m.setBotId(b.getId());m.setTenantId(b.getTenantId());m.setUserId(c.getStr("ownerUserId"));
                m.setTargetSessionId(s.getId());m.setDirection(Direction.INBOUND.getCode());m.setContentType(file?Content.FILE.getCode():Content.TEXT.getCode());
                m.setContent(file?StringUtils.defaultIfBlank(media.getStr("file_name"),"上传附件"):StringUtils.left(row.getStr("text"),20000));
                m.setInboundKey(inbound);m.setExternalMessageId(row.getStr("id"));m.setReplyContext(context.toString());m.setProviderUuid(UUID.randomUUID().toString());
                m.setRequestId("im-"+m.getId());m.setProcessState(Process.QUEUED.getCode());m.setSessionId(s.getSessionId());
                if(messages.acceptInbound(m,node,version)!=1)throw lost();
                if(file && row.getInt("fileCount",1)>1) {
                    m.setProcessState(Process.FAILED.getCode());m.setSendState(Send.FAILED.getCode());m.setLastError("单条消息包含多个附件，请拆分后发送");
                    messages.progress(m,node,version);log.warn("真人消息附件数量超出接收范围 botKey={}, messageId={}, externalUserId={}",b.getId(),m.getId(),row.getStr("sender"));
                }
                log.info("真人会话入站 botKey={}, platform={}, ownerUserId={}, externalUserId={}, chatId={}, externalMessageId={}, messageId={}",
                        b.getId(),b.getPlatform(),m.getUserId(),row.getStr("sender"),s.getExternalChatId(),row.getStr("id"),m.getId());
            }
        });
        }
    }
    /** 飞书优先核对平台回执，企微按对侧会话、发送时间和正文核对系统发送记录。 */
    private boolean peerReply(ImBotEntity bot,ImBotSessionEntity target,String self,Long peer,JSONObject row) {
        if(peer==null || StringUtils.isBlank(row.getStr("text")))return false;
        String chat=Platform.WECOM.getCode().equals(bot.getPlatform()) && SessionType.P2P.getCode().equals(target.getSessionType())
                ? self : target.getExternalChatId();
        List<ImBotSessionEntity> opposite=sessions.selectList(new LambdaQueryWrapper<ImBotSessionEntity>()
                .select(ImBotSessionEntity::getId).eq(ImBotSessionEntity::getBotId,peer)
                .eq(ImBotSessionEntity::getExternalChatId,chat).eq(ImBotSessionEntity::getSessionType,target.getSessionType()));
        if(opposite.isEmpty())return false;
        List<Long> targets=new ArrayList<>();for(ImBotSessionEntity session:opposite)targets.add(session.getId());
        java.time.LocalDateTime time=java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(row.getLong("time")),java.time.ZoneId.systemDefault());
        List<ImBotMessageEntity> sent=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>()
                .in(ImBotMessageEntity::getTargetSessionId,targets).in(ImBotMessageEntity::getSendState,
                        Send.SENDING.getCode(),Send.UNKNOWN.getCode(),Send.ACCEPTED.getCode())
                .between(ImBotMessageEntity::getUpdateTime,time.minusMinutes(2),time.plusMinutes(2)));
        for(ImBotMessageEntity message:sent) {
            if(StringUtils.isNotBlank(message.getProviderMessageId())) {
                if(message.getProviderMessageId().equals(row.getStr("id")))return true;
            } else if(row.getStr("text").equals(message.getReplyContent()))return true;
        }
        return false;
    }
    private static BizException lost(){return new BizException(Error.LEASE_LOST.getCode(),"接管归属、配置或启用状态已变化");}
    /** 到达末尾时本批直接回到开头，少量会话不会隔一轮才被读取。 */
    List<ImBotSessionEntity> nextBatch(Long botId,String userId,long after){
        LambdaQueryWrapper<ImBotSessionEntity> query=new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,botId)
                .eq(ImBotSessionEntity::getUserId,userId).gt(ImBotSessionEntity::getId,after).orderByAsc(ImBotSessionEntity::getId).last("LIMIT 5");
        List<ImBotSessionEntity> batch=sessions.selectList(query);
        if(batch.isEmpty() && after>0)return nextBatch(botId,userId,0);return batch;
    }

    /** 真人模式的轻量通道，无WebSocket；网络操作仅由既有后台线程执行。 */
    private final class UserChannel implements ImBotChannel {
        /** 建立时的配置版本快照。 */
        private final ImBotEntity expected;
        /** 当前节点标识。 */
        private final String node;
        /** 当前归属代次。 */
        private final Long version;
        /** 本节点租约有效性检查。 */
        private final BooleanSupplier live;
        /** 本地通道关闭标记。 */
        private volatile boolean closed;
        UserChannel(ImBotEntity b,String node,Long version,BooleanSupplier live){this.expected=b;this.node=node;this.version=version;this.live=live;}
        @Override public boolean online(){return !closed && live.getAsBoolean();}
        @Override public void close(){closed=true;}
        @Override public ImBotFile downloadFile(ImBotMessageEntity message,JSONObject source)throws Exception {
            if(!online())throw lost();
            ImBotEntity b=current(expected,node,version);
            // 令牌刷新仍由单批拉取统一持久化；下载不自行消费一次性refresh_token。
            return gateway.download(b,ImBotTakeoverConfig.read(b,environment),message,source);
        }
        void poll(){
            if(!online())return;
            try{
                ImBotEntity b=current(expected,node,version);JSONObject c=credentials(b,node,version);
                if(!c.getBool("targetsSeeded",false)){
                    for(String chat:ImBotTakeoverConfig.list(c,"privateChatIds"))target(b,c,chat,SessionType.P2P.getCode(),null,node,version);
                    for(String chat:ImBotTakeoverConfig.list(c,"groupChatIds"))target(b,c,chat,SessionType.GROUP.getCode(),null,node,version);
                    c.set("targetsSeeded",true);
                }
                discover(b,c,node,version);
                long after=c.getLong("sessionAfter",0L);
                List<ImBotSessionEntity> batch=nextBatch(b.getId(),c.getStr("ownerUserId"),after);
                if(batch.isEmpty())c.set("sessionAfter",0);else for(ImBotSessionEntity s:batch){if(!online())return;read(b,c,s,node,version);c.set("sessionAfter",s.getId());}
                checkpoint(b,node,version,c);
                bots.connection(b.getId(),node,version,Connection.ONLINE.getCode(),null);
            }catch(Exception e){pause(e);}
        }
        private void pause(Exception e){
            closed=true;log.warn("真人接管已暂停 botKey={}, platform={}, node={}, ownerVersion={}\n{}",expected.getId(),expected.getPlatform(),node,version,safeStackTrace(e));
            // 配置变更/租约丢失不覆盖新持有者；其他失败持久停用，避免每十秒重复报错。
            bots.pauseTakeover(expected,node,version,"真人接管拉取或发送检查失败，已停止自动监测，请检查后台日志及本人授权后重新启用");
        }
        @Override public CompletionStage<Receipt> send(ImBotMessageEntity row,ImBotSessionEntity target){
            try{
                if(!online() || !ImBotTakeoverConfig.user(row))return CompletableFuture.completedFuture(new Receipt(Send.CANCELED.getCode(),null,"不是当前真人接管消息"));
                if(!unanswered(expected,node,version,row))return CompletableFuture.completedFuture(new Receipt(Send.CANCELED.getCode(),null,"本人已回复，取消自动回复"));
                ImBotEntity b=current(expected,node,version);JSONObject c=ImBotTakeoverConfig.read(b,environment);
                return CompletableFuture.completedFuture(gateway.send(b,c,target.getExternalChatId(),row.getReplyContent(),row.getProviderUuid()));
            }catch(Exception e){
                pause(e);return CompletableFuture.completedFuture(new Receipt(Send.UNKNOWN.getCode(),null,"真人发送或复查未确认，已停止，不自动重发"));
            }
        }
    }
}
