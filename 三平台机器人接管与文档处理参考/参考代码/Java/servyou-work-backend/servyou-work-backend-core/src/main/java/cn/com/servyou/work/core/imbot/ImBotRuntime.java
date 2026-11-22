package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Process;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.config.ChatScheduleNodeIdentity;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 一套 Bot 行内租约，续约扫描不等待网络或模型；同一会话只派发最早未结束消息。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ImBotRuntime {
    private final ImBotMapper bots;private final ImBotMessageMapper messages;private final ImBotSessionMapper sessions;
    private final ImBotServiceImpl service;private final ImBotExecution execution;private final ImBotInbox inbox;
    private final ImBotFiles files;
    private final WecomBotChannel.Factory wecom;private final FeishuBotChannel.Factory feishu;
    private final ThreadPoolTaskExecutor workers,inbound,auxiliary;private final TransactionTemplate tx;private final Environment environment;private final String node;
    private final Map<Long,Owner> owners=new ConcurrentHashMap<>();private volatile boolean closed;
    @org.springframework.beans.factory.annotation.Autowired
    private ImBotTakeover takeover;
    /** 钉钉 Stream 连接工厂。 */
    @org.springframework.beans.factory.annotation.Autowired
    private DingTalkBotChannel.Factory dingTalk;

    private ImBotChannel.Factory factory(ImBotEntity bot) {
        if (Platform.DINGTALK.getCode().equals(bot.getPlatform())) return dingTalk;
        return Platform.WECOM.getCode().equals(bot.getPlatform()) ? wecom : feishu;
    }
    public ImBotRuntime(ImBotMapper bots,ImBotMessageMapper messages,ImBotSessionMapper sessions,ImBotServiceImpl service,ImBotExecution execution,
                        ImBotInbox inbox,ImBotFiles files,WecomBotChannel.Factory wecom,FeishuBotChannel.Factory feishu,
                        @Qualifier("conversationExecutor") ThreadPoolTaskExecutor workers,@Qualifier("imBotInboundExecutor") ThreadPoolTaskExecutor inbound,
                        @Qualifier("taskExecutor") ThreadPoolTaskExecutor auxiliary,
                        TransactionTemplate tx,Environment environment,ChatScheduleNodeIdentity identity) {
        this.bots=bots;this.messages=messages;this.sessions=sessions;this.service=service;this.execution=execution;this.inbox=inbox;this.wecom=wecom;this.feishu=feishu;
        this.files=files;this.workers=workers;this.inbound=inbound;this.auxiliary=auxiliary;this.tx=tx;this.environment=environment;this.node=identity.getNodeId();
    }
    @Scheduled(fixedDelay=15000,initialDelay=5000)
    public void renew() {
        if(closed)return;
        for(Owner o:owners.values()) {
            try {if(bots.renew(o.bot.getId(),node,o.version,60)!=1) drop(o);else o.renewed();}
            catch(Exception e){failure(o,"续约失败，关闭本地连接",null,e);drop(o);}
        }
        List<ImBotEntity> candidates=bots.selectList(new LambdaQueryWrapper<ImBotEntity>().and(q->q.eq(ImBotEntity::getEnabled,ImBotServiceImpl.flag(true))
                .or().apply("EXISTS (SELECT 1 FROM sw_im_bot_message m WHERE m.bot_id=sw_im_bot.id AND m.process_state IN ({0},{1},{2},{3}))",
                        Process.RUNNING.getCode(),Process.WAITING_INPUT.getCode(),Process.RECOVERING.getCode(),Process.CANCEL_REQUESTED.getCode())));
        Collections.shuffle(candidates);
        for(ImBotEntity b:candidates) if(!owners.containsKey(b.getId()) && bots.claim(b.getId(),node,60)==1) {
            ImBotEntity owned=bots.selectById(b.getId());if(node.equals(owned.getOwnerNode())) owners.put(b.getId(),new Owner(owned));
        }
    }
    @Scheduled(fixedDelay=10000,initialDelay=6000)
    public void dispatch() {
        if(closed)return;
        for(Owner o:owners.values()) {
            if(!o.live()){drop(o);continue;}
            try {
                ImBotEntity fresh=bots.selectById(o.bot.getId());
                if(!o.version.equals(fresh.getOwnerVersion()) || !node.equals(fresh.getOwnerNode())){drop(o);continue;}
                if(!fresh.getConfigVersion().equals(o.bot.getConfigVersion())) o.disconnect();o.bot=fresh;
                if(!ImBotServiceImpl.yes(fresh.getEnabled())) {o.disconnect();if(!Connection.ERROR.getCode().equals(fresh.getConnectionState()))bots.connection(fresh.getId(),node,o.version,Connection.DISABLED.getCode(),null);}
                else if(o.channel==null && !o.connecting.get() && System.nanoTime()>o.retryAfter) connect(o);
                if(fresh.getParentBotId()==null && ImBotTakeoverConfig.user(fresh) && ImBotServiceImpl.yes(fresh.getEnabled())) bindingListener(o,fresh);
                if(o.channel!=null && !o.channel.online() && System.nanoTime()-o.channelSince>TimeUnit.SECONDS.toNanos(30)) {o.disconnect();o.retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);}
                if(o.channel!=null && !ImBotTakeoverConfig.user(fresh)) bots.connection(fresh.getId(),node,o.version,o.channel.online()?Connection.ONLINE.getCode():Connection.CONNECTING.getCode(),null);
                if(o.channel!=null && ImBotTakeoverConfig.user(fresh) && ImBotServiceImpl.yes(fresh.getEnabled())
                        && ImBotServiceImpl.yes(fresh.getAutoProcessEnabled()) && o.pulling.compareAndSet(false,true)) {
                    ImBotChannel channel=o.channel;
                    submit(o,null,()->{try{takeover.poll(channel);}finally{o.pulling.set(false);}},()->o.pulling.set(false));
                }
                if(ImBotTakeoverConfig.user(fresh) && !ImBotServiceImpl.yes(fresh.getEnabled())){execution.closeBot(fresh.getId());continue;}
                dispatchMessages(o);
            } catch(Exception e){failure(o,"扫描失败",null,e);}
        }
    }
    private void connect(Owner o) {
        if(!o.connecting.compareAndSet(false,true))return;
        final ImBotEntity connectingBot=o.bot;
        submit(o,null,()->{
            try {
                if(!o.live())return;bots.connection(o.bot.getId(),node,o.version,Connection.CONNECTING.getCode(),null);
                ImBotChannel channel;
                if(ImBotTakeoverConfig.user(connectingBot))channel=takeover.connect(connectingBot,node,o.version,o::live);
                else {
                    String secret=AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),connectingBot.getSecretCipher());
                    channel=factory(connectingBot).connect(connectingBot,secret,event->receive(o,event));
                }
                synchronized(o) {if(!o.live() || !ImBotServiceImpl.yes(service.bot(o.bot.getId()).getEnabled()) || !connectingBot.getConfigVersion().equals(service.bot(o.bot.getId()).getConfigVersion()))channel.close();else {o.channel=channel;o.channelSince=System.nanoTime();}}
            } catch(Exception e){
                failure(o,"连接或认证失败",null,e);
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                o.retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);bots.connection(o.bot.getId(),node,o.version,Connection.ERROR.getCode(),"连接或认证失败，请检查凭据、应用发布及事件订阅");
            }
            finally{o.connecting.set(false);}
        },()->o.connecting.set(false));
    }
    /** 旧单人配置继续运行时，按绑定码有效期接收新人员的绑定。 */
    private void bindingListener(Owner o,ImBotEntity bot) {
        java.time.LocalDateTime deadline=service.bindingDeadline(bot.getId());
        synchronized(o) {
            if(deadline==null) { o.closeBinding();return; }
            if(o.binding!=null || deadline.equals(o.bindingAttempt))return;
            o.bindingAttempt=deadline;
        }
        submit(o,null,()->{
            try {
                String secret=AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),bot.getSecretCipher());
                ImBotChannel c=factory(bot).connect(bot,secret,event->{if(o.live())receive(o,event,true);});
                synchronized(o){if(o.live() && deadline.equals(o.bindingAttempt))o.binding=c;else c.close();}
            } catch(Exception e){failure(o,"绑定监听失败，请重新生成绑定码",null,e);}
        },()->{synchronized(o){o.bindingAttempt=null;}});
    }
    private void receive(Owner o,ImBotChannel.Inbound event) {receive(o,event,false);}
    private void receive(Owner o,ImBotChannel.Inbound event,boolean bindingOnly) {
        // 记录平台回调中的原始用户标识，供排查工作台账号绑定；不记录正文和回复上下文。
        log.info("机器人收到入站消息 botKey={}, platform={}, externalAppId={}, node={}, ownerVersion={}, externalUserId={}, externalMessageId={}",
                o.bot.getId(),o.bot.getPlatform(),o.bot.getExternalAppId(),node,o.version,event.getExternalUserId(),event.getMessageId());
        if(!o.live()) {
            BizException expired=new BizException(Error.LEASE_LOST.getCode(),"Bot租约已失效");
            failure(o,"入站租约已失效",null,event,expired);throw expired;
        }
        if(Platform.WECOM.getCode().equals(o.bot.getPlatform())) {
            try {inbound.execute(()->{
                try {if(bindingOnly) inbox.accept(o.bot.getId(),node,o.version,event,true);else inbox.accept(o.bot.getId(),node,o.version,event);}
                catch(RuntimeException e){failure(o,"企微入站事务失败",null,event,e);}
            });}catch(RejectedExecutionException e){failure(o,"企微入站过载，事件未入库",null,event,e);}
        } else {
            // 回调至多等2秒；只有短事务提交成功才返回成功 ACK。
            String label=Platform.FEISHU.getCode().equals(o.bot.getPlatform())?"飞书":"钉钉";
            Future<?> stored=null;
            try {
                stored=inbound.submit(()->{
                    try {if(bindingOnly) inbox.accept(o.bot.getId(),node,o.version,event,true);else inbox.accept(o.bot.getId(),node,o.version,event);}
                    catch(RuntimeException e){failure(o,label+"入站事务失败",null,event,e);throw e;}
                });
                stored.get(2,TimeUnit.SECONDS);
            }
            catch(Exception e){
                if(stored!=null)stored.cancel(true);
                if(e instanceof InterruptedException)Thread.currentThread().interrupt();
                failure(o,label+"入库未确认，请平台重推",null,event,e);
                throw new BizException(Error.INBOUND_FAILED.getCode(),"入库未确认，请平台重推",e);
            }
        }
    }
    private void dispatchMessages(Owner o) {
        // 先取临时附件，即使同一会话上一轮尚在运行，也不会等到企微五分钟链接失效。
        if(o.channel!=null && o.channel.online())for(ImBotMessageEntity file:messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>()
                .eq(ImBotMessageEntity::getBotId,o.bot.getId()).eq(ImBotMessageEntity::getDirection,Direction.INBOUND.getCode())
                .eq(ImBotMessageEntity::getContentType,Content.FILE.getCode()).isNull(ImBotMessageEntity::getFileId)
                .eq(ImBotMessageEntity::getProcessState,Process.QUEUED.getCode()).orderByAsc(ImBotMessageEntity::getId).last("LIMIT 100"))) {
            if(o.working.add(file.getId()))submit(o,file.getId(),()->{try{files.prepare(o.bot,node,o.version,file,o.channel,o::live);}finally{o.working.remove(file.getId());}},()->o.working.remove(file.getId()));
        }
        // 关联子查询只挑各会话最早未结束行，RECOVERING 不会导致其他会话分页饥饿。
        List<ImBotMessageEntity> rows=messages.selectDispatch(o.bot.getId(),100);
        for(ImBotMessageEntity m:rows) {
            if(Content.FILE.getCode().equals(m.getContentType()) && m.getFileId()==null)continue;
            if(Process.QUEUED.getCode().equals(m.getProcessState()) && !ImBotServiceImpl.yes(o.bot.getEnabled())) continue;
            // 通用 chat 是长调用；已有观察关联后的刷新和答题不能被原执行的 working 标记挡住。
            boolean observing=execution.observing(m.getId());Set<Long> active=observing?o.projecting:o.working;
            if(active.add(m.getId()))submit(observing?auxiliary:workers,o,m.getId(),()->{
                try{execution.handle(o.bot,node,o.version,m,o::live);}
                finally{active.remove(m.getId());}
            },()->active.remove(m.getId()));
        }
        if((o.channel==null || !o.channel.online()) && !o.sending.get()) {
            for(ImBotMessageEntity stale:messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,o.bot.getId()).eq(ImBotMessageEntity::getSendState,Send.SENDING.getCode()).last("LIMIT 10"))) {
                messages.acknowledge(stale,Send.UNKNOWN.getCode(),null,"连接中断，上次发送结果未知",node,o.version);
            }
        }
        if(o.channel==null || !o.channel.online() || !o.sending.compareAndSet(false,true))return;
        if(System.nanoTime()<o.nextSend) {o.sending.set(false);return;}
        submit(o,null,()->sendNext(o),()->o.sending.set(false));
    }
    private void sendNext(Owner o) {
        boolean asynchronous=false;
        try {
            List<ImBotMessageEntity> candidates=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,o.bot.getId())
                    .in(ImBotMessageEntity::getSendState,Send.PENDING.getCode(),Send.SENDING.getCode()).orderByAsc(ImBotMessageEntity::getId).last("LIMIT 1"));
            if(candidates.isEmpty() || !o.live())return;
            ImBotMessageEntity m=candidates.get(0);
            if(!ImBotTakeoverConfig.matches(service.bot(o.bot.getId()),m,environment)) {
                messages.transition(m.getId(),node,o.version,null,m.getSendState(),null,Send.CANCELED.getCode());return;
            }
            if(Send.SENDING.getCode().equals(m.getSendState())) {messages.acknowledge(m,Send.UNKNOWN.getCode(),null,"上次发送结果不明，不自动重发",node,o.version);return;}
            if(m.getExpiresAt()!=null && !m.getExpiresAt().isAfter(bots.databaseNow())) {
                messages.acknowledge(m,Send.FAILED.getCode(),null,"已超过发送有效期",node,o.version);return;
            }
            ImBotSessionEntity target=sessions.selectById(m.getTargetSessionId());
            if(service.sendable(service.bot(o.bot.getId()),target)) {
                try {service.verifySender(m);}
                catch(Exception unavailable) {
                    failure(o,"发送前账户身份核查失败",m.getId(),unavailable);
                    messages.acknowledge(m,Send.FAILED.getCode(),null,"发送前账户身份核查失败，已停止自动发送，请检查后台日志",node,o.version);return;
                }
            }
            boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
                ImBotEntity fresh=service.lockBot(o.bot.getId());
                if(m.getExpiresAt()!=null && !m.getExpiresAt().isAfter(bots.databaseNow())) {
                    messages.acknowledge(m,Send.FAILED.getCode(),null,"已超过发送有效期",node,o.version);return false;
                }
                if(!service.sendable(fresh,target)) {
                    messages.transition(m.getId(),node,o.version,null,Send.PENDING.getCode(),null,Send.CANCELED.getCode());return false;
                }
                return messages.transition(m.getId(),node,o.version,null,Send.PENDING.getCode(),null,Send.SENDING.getCode())==1;
            }));
            if(!accepted || !o.live())return;
            m.setSendState(Send.SENDING.getCode());
            // 每 Bot 串行、企微按1000/小时上限保守间隔3.7秒，飞书200毫秒；不阻塞线程睡眠。
            o.nextSend=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Platform.WECOM.getCode().equals(o.bot.getPlatform())?3700:210);
            CompletionStage<ImBotChannel.Receipt> receipt=Content.FILE.getCode().equals(m.getContentType()) && Direction.OUTBOUND.getCode().equals(m.getDirection())
                    ?files.send(o.channel,m,target):o.channel.send(m,target);asynchronous=true;
            receipt.whenComplete((r,error)->{
                if(error!=null)failure(o,"异步发送回执异常",m.getId(),error);
                try { inbound.execute(()->{
                try {
                    if(!o.live())return;
                    messages.acknowledge(m,error==null?r.getState():Send.UNKNOWN.getCode(),
                            error==null?r.getProviderMessageId():null,error==null?r.getError():"发送结果未知",node,o.version);
                } catch(Exception e){failure(o,"发送回执入库失败",m.getId(),e);}
                finally{o.sending.set(false);}
                }); } catch(RejectedExecutionException rejected) {failure(o,"发送回执入库过载",m.getId(),rejected);o.sending.set(false);}
            });
        } catch(Exception e){failure(o,"发送未确认",null,e);}
        finally{if(!asynchronous)o.sending.set(false);}
    }
    private void submit(Owner o,Long messageId,Runnable task,Runnable rejected) {
        submit(auxiliary,o,messageId,task,rejected);
    }
    private void submit(ThreadPoolTaskExecutor executor,Owner o,Long messageId,Runnable task,Runnable rejected) {
        Thread caller=Thread.currentThread();
        try {executor.execute(()->{
            // 共用线程池采用 CallerRunsPolicy 时也禁止回退到扫描线程执行网络或模型调用。
            if(Thread.currentThread()==caller)throw new RejectedExecutionException("机器人工作池已满，等待后续扫描");
            try{task.run();}catch(RuntimeException e){failure(o,"异步任务失败",messageId,e);}
        });}
        catch(RejectedExecutionException e){failure(o,"工作池过载，等待下次扫描",messageId,e);rejected.run();}
    }
    private void failure(Owner o,String action,Long messageId,Throwable error) {
        failure(o,action,messageId,null,error);
    }
    private void failure(Owner o,String action,Long messageId,ImBotChannel.Inbound event,Throwable error) {
        log.warn("机器人{} botKey={}, platform={}, externalAppId={}, node={}, ownerVersion={}, messageId={}, externalUserId={}, externalMessageId={}\n{}",
                action,o.bot.getId(),o.bot.getPlatform(),o.bot.getExternalAppId(),node,o.version,messageId,
                event==null?null:event.getExternalUserId(),event==null?null:event.getMessageId(),safeStackTrace(error));
    }
    private void drop(Owner o) {owners.remove(o.bot.getId(),o);o.valid=false;o.disconnect();execution.closeBot(o.bot.getId());}
    @PreDestroy public void close() {
        closed=true;for(Owner o:owners.values()) {drop(o);try{bots.release(o.bot.getId(),node,o.version);}catch(Exception e){failure(o,"归属释放失败，等待租约到期",null,e);}}
    }
    /** 本节点当前持有的机器人连接及少量在途标记，等待业务仍保存在数据库。 */
    private final class Owner {
        /** 最近加载的机器人配置。 */
        volatile ImBotEntity bot;
        /** 本次持有连接的固定归属代次。 */
        final Long version;
        /** 本地保守停止的单调时钟截止值。 */
        volatile long safeUntil;
        /** 下次允许重建连接的单调时钟时刻。 */
        volatile long retryAfter;
        /** 下次允许发送的单调时钟时刻。 */
        volatile long nextSend;
        /** 当前连接建立时的单调时钟值。 */
        volatile long channelSince;
        /** 此归属对象是否仍有效。 */
        volatile boolean valid=true;
        /** 当前机器人平台连接。 */
        volatile ImBotChannel channel;
        /** 避免同一机器人重复提交连接工作。 */
        final AtomicBoolean connecting=new AtomicBoolean();
        /** 保持同一机器人只执行一条在途发送。 */
        final AtomicBoolean sending=new AtomicBoolean();
        /** 真人账号最多一个在途拉取批次，积压不进入节点等待队列。 */
        final AtomicBoolean pulling=new AtomicBoolean();
        /** 当前已经提交到现有工作池的消息标识。 */
        final Set<Long> working=ConcurrentHashMap.newKeySet();
        /** 原执行持续运行期间独立投递短刷新及问答任务，避免被长调用阻塞。 */
        final Set<Long> projecting=ConcurrentHashMap.newKeySet();
        Owner(ImBotEntity b){bot=b;version=b.getOwnerVersion();renewed();nextSend=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);}
        void renewed(){safeUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);}
        boolean live(){return valid && !closed && System.nanoTime()<safeUntil;}
        /** 旧配置领取新绑定码期间的机器人连接。 */
        ImBotChannel binding;
        /** 最近尝试的绑定截止时间。 */
        java.time.LocalDateTime bindingAttempt;
        synchronized void closeBinding(){bindingAttempt=null;if(binding!=null){binding.close();binding=null;}}
        synchronized void disconnect(){closeBinding();ImBotChannel c=channel;channel=null;if(c!=null)c.close();}
    }
}
