package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.enums.ImBotEnums.Connection;
import cn.com.servyou.work.api.enums.ImBotEnums.Process;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.core.utils.*;
import cn.com.servyou.work.core.manager.admin.AdminOpLogRecorder;
import cn.com.servyou.work.core.config.ChatScheduleNodeIdentity;
import cn.com.servyou.work.core.config.MybatisPlusConfig;
import cn.com.servyou.work.core.impl.conversation.ConversationServiceImpl;
import cn.com.servyou.work.core.impl.conversation.ProjectSpaceAccessService;
import cn.com.servyou.work.core.manager.accountcenter.AccountCenterManagerImpl;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.hutool.core.io.IoUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import gov.chinatax.apaas.zhzx.api.session.dto.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只在显式授权的真实MySQL上运行；测试数据独立连接核查清理，DDL保留。 */
@EnabledIfSystemProperty(named="im.mysql.url",matches="jdbc:mysql:.*")
class ImBotMySqlIntegrationTest {
    private static DriverManagerDataSource data;
    private static SqlSessionTemplate sql;
    private static TransactionTemplate tx;
    private ImBotMapper bots;private ImBotUserMapper users;private ImBotSessionMapper sessions;private ImBotMessageMapper messages;
    private ImBotServiceImpl service;private ImBotInbox inbox;private String marker;private final List<Long> botIds=new ArrayList<>();
    private String userId;private ImBotEntity b;
    @BeforeAll static void initialize()throws Exception {
        assertNotNull(System.getenv("IM_MYSQL_PASSWORD"),"真实库凭据须从环境提供");
        data=new DriverManagerDataSource(System.getProperty("im.mysql.url"),System.getProperty("im.mysql.user"),System.getenv("IM_MYSQL_PASSWORD"));
        try(java.sql.Connection c=data.getConnection();Statement st=c.createStatement()) {
            try(ResultSet rs=st.executeQuery("SELECT DATABASE(),VERSION()")){assertTrue(rs.next());assertEquals("test_servyou_work",rs.getString(1));System.out.println("IM bot live MySQL: "+rs.getString(1)+" / "+rs.getString(2));}
            try(InputStream in=new ClassPathResource("sql/upgrade/20261116.sql").getInputStream()) {
                for(String statement:IoUtil.readUtf8(in).replaceAll("(?m)^--.*$","").split(";"))
                    if(statement.trim().startsWith("CREATE TABLE IF NOT EXISTS sw_im_bot"))st.execute(statement);
            }
            try(InputStream in=new ClassPathResource("sql/upgrade/20261117.sql").getInputStream()) {
                for(String statement:IoUtil.readUtf8(in).replaceAll("(?m)^--.*$","").split(";")) {
                    String table=statement.contains("ALTER TABLE sw_im_bot_message")?"sw_im_bot_message":"sw_im_bot";
                    String column=table.equals("sw_im_bot_message")?"file_id":"document_config_cipher";
                    if(statement.trim().isEmpty())continue;
                    try(ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,table,column)) {
                        if(!columns.next())st.execute(statement);
                    }
                }
            }
            try(ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,"sw_im_bot","parent_bot_id")) {
                if(!columns.next()) {
                    try(InputStream in=new ClassPathResource("sql/upgrade/20261119.sql").getInputStream()) {
                        String stmt=Arrays.stream(IoUtil.readUtf8(in).replaceAll("(?m)^--.*$","").split(";")).map(String::trim)
                            .filter(s->s.startsWith("ALTER TABLE sw_im_bot ADD COLUMN parent_bot_id ")).findFirst().get();
                        st.execute(stmt);
                    }
                }
            }
            // 仅应用本功能的三列升级，不执行同日其他业务迁移。
            try(InputStream in=new ClassPathResource("sql/upgrade/20261118.sql").getInputStream()) {
                java.util.regex.Matcher ddl=java.util.regex.Pattern.compile("(?m)^ALTER TABLE (sw_im_bot(?:_session)?) ADD COLUMN (identity_mode|takeover_config_cipher|takeover_state) [^;]+;")
                        .matcher(IoUtil.readUtf8(in));
                int found=0;while(ddl.find()) {found++;try(ResultSet columns=c.getMetaData().getColumns(c.getCatalog(),null,ddl.group(1),ddl.group(2))){if(!columns.next())st.execute(ddl.group());}}
                assertEquals(3,found);
            }
        }
        MybatisConfiguration config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        // 使用线上完整拦截器链，真实 MySQL 验证也必须经过数据权限 SQL 解析。
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(ExternalIdentity.defaults()));
        for(Class<?> mapper:Arrays.asList(ImBotMapper.class,ImBotUserMapper.class,ImBotSessionMapper.class,ImBotMessageMapper.class,AdminOpLogMapper.class,AiConversationMapper.class,MessageRuntimeMetricMapper.class))config.addMapper(mapper);
        MybatisSqlSessionFactoryBean factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(data);factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapper/ImBotMapper.xml"),new ClassPathResource("mapper/ImBotMessageMapper.xml"),new ClassPathResource("mapper/ImBotSessionMapper.xml"),new ClassPathResource("mapper/MessageRuntimeMetricMapper.xml"));
        sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));tx=new TransactionTemplate(new DataSourceTransactionManager(data));
    }
    @BeforeEach void setup() {
        marker="imtest-"+UUID.randomUUID().toString().replace("-","");userId=marker;
        bots=sql.getMapper(ImBotMapper.class);users=sql.getMapper(ImBotUserMapper.class);sessions=sql.getMapper(ImBotSessionMapper.class);messages=sql.getMapper(ImBotMessageMapper.class);
        service=new ImBotServiceImpl(bots,users,sessions,messages,null,null,new AdminOpLogRecorder(sql.getMapper(AdminOpLogMapper.class)),tx,new StandardEnvironment());
        inbox=new ImBotInbox(service,bots,users,sessions,messages,tx,new StandardEnvironment());b=createBot(marker);
    }
    @AfterEach void cleanup()throws Exception {
        UserInfoUtil.clearUserInfo();AdminTenantContext.clear();
        try(java.sql.Connection c=data.getConnection()) {
            c.setAutoCommit(false);
            for(String table:Arrays.asList("sw_im_bot_message","sw_im_bot_session","sw_im_bot_user","sw_im_bot")) {
                String column=table.equals("sw_im_bot")?"id":"bot_id";
                try(PreparedStatement st=c.prepareStatement("DELETE FROM "+table+" WHERE "+column+"=?")){for(Long key:botIds){st.setLong(1,key);st.executeUpdate();}}
            }
            try(PreparedStatement st=c.prepareStatement("DELETE FROM sw_admin_op_log WHERE operator_id=?")){st.setString(1,userId);st.executeUpdate();}
            try(PreparedStatement st=c.prepareStatement("DELETE FROM sw_message_runtime_metric WHERE user_id=?")){st.setString(1,userId);st.executeUpdate();}
            c.commit();
        }
        try(java.sql.Connection c=data.getConnection();PreparedStatement st=c.prepareStatement("SELECT (SELECT COUNT(*) FROM sw_im_bot WHERE create_user_id=?)+(SELECT COUNT(*) FROM sw_im_bot_message WHERE user_id=?)+(SELECT COUNT(*) FROM sw_im_bot_user WHERE user_id=?)+(SELECT COUNT(*) FROM sw_im_bot_session WHERE user_id=?)")) {
            for(int i=1;i<=4;i++)st.setString(i,userId);try(ResultSet rs=st.executeQuery()){assertTrue(rs.next());assertEquals(0,rs.getInt(1),"真实测试数据必须清理");}
        }
    }
    private ImBotEntity createBot(String appId) {
        ImBotEntity row=new ImBotEntity();row.setId(IdWorker.getId());row.setTenantId("ZH000");row.setPlatform(Platform.FEISHU.getCode());row.setExternalAppId(appId);row.setAppIdKey(ImBotServiceImpl.hash(appId));row.setName(marker);
        row.setSecretCipher("ENC(test-fixture)");row.setEnabled(ImBotServiceImpl.flag(true));row.setAutoProcessEnabled(ImBotServiceImpl.flag(true));row.setGroupEnabled(ImBotServiceImpl.flag(false));row.setAllowedGroupChatIds("[]");
        row.setOwnerNode("imtest-reservation");row.setLeaseUntil(bots.databaseNow().plusHours(1));
        row.setConfigVersion(1L);row.setOwnerVersion(0L);row.setConnectionState(Connection.DISABLED.getCode());row.setCreateUserId(userId);row.setUpdateUserId(userId);bots.insert(row);botIds.add(row.getId());return row;
    }
    /** 只测试Mapper租约竞争时不让共享测试环境的真实监听器参与竞争。 */
    private void isolateLeaseCompetition() {
        bots.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotEntity>()
                .eq(ImBotEntity::getId,b.getId()).set(ImBotEntity::getEnabled,ImBotServiceImpl.flag(false)));
    }
    private void own() {
        // 共享测试库中可能有真实运行节点，测试记录预留租约避免被线上扫描器抢走。
        tx.execute(status->{bots.release(b.getId(),"imtest-reservation",0L);assertEquals(1,bots.claim(b.getId(),"node-a",60));return null;});
        b=bots.selectById(b.getId());
    }
    private ImBotUserEntity authorize(String external) {
        ImBotUserEntity row=new ImBotUserEntity();row.setBotId(b.getId());row.setTenantId(b.getTenantId());row.setUserId(userId);row.setUserName(marker);row.setAuthorized(ImBotServiceImpl.flag(true));
        if(external!=null){row.setExternalUserId(external);row.setExternalUserKey(ImBotServiceImpl.hash(external));}users.insert(row);return row;
    }
    private ImBotChannel.Inbound event(String id,String external,String text) {
        ImBotChannel.Inbound e=new ImBotChannel.Inbound();e.setExternalUserId(external);e.setChatId("chat-"+marker);e.setSessionType(SessionType.P2P.getCode());e.setMessageId(id);e.setContent(text);return e;
    }
    private void asUser(Runnable action) {
        asUser(userId, "ZH000", action);
    }
    private void asUser(String accountId, String tenantId, Runnable action) {
        ETaxSwryAccountDTO person=new ETaxSwryAccountDTO();person.setSwrysfDm(accountId);person.setSwryDm(accountId);person.setSwrymc(marker);person.setSwjgDm("test");
        ETaxUserAccountDTO account=new ETaxUserAccountDTO();account.setTenantId(tenantId);account.setSwryAccount(person);
        UserInfoUtil.withVerifiedAccount(account,ExternalIdentity.defaults(),"fixture-credential",action);
    }
    private cn.hutool.json.JSONObject takeoverConfig() {
        own();authorize("takeover-self");
        cn.hutool.json.JSONObject c=JSONUtil.createObj().set("ownerUserId",userId).set("externalUserId","takeover-self")
                .set("generation",UUID.randomUUID().toString()).set("startedAt",System.currentTimeMillis()-60000);
        b.setIdentityMode(Identity.USER.getCode());b.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(c,new StandardEnvironment()));bots.updateById(b);return c;
    }
    @Test void runtimeMetricTerminalDetectionReadsFactsKeptByRealMySqlUpsert(){
        MessageRuntimeMetricMapper metrics=sql.getMapper(MessageRuntimeMetricMapper.class);
        cn.com.servyou.work.core.monitor.ConversationAnomalyDetector detector=org.mockito.Mockito.mock(cn.com.servyou.work.core.monitor.ConversationAnomalyDetector.class);
        cn.com.servyou.work.core.monitor.RuntimeMetricCollector collector=new cn.com.servyou.work.core.monitor.RuntimeMetricCollector(metrics,detector);
        Long id=IdWorker.getId();String run=UUID.randomUUID().toString();java.time.LocalDateTime now=java.time.LocalDateTime.now();
        asUser(()->tx.executeWithoutResult(status->{
            collector.begin(id,marker,userId,"test-model","test-provider",null,null,null,now.minusSeconds(8),"test-node");
            collector.onEvent(id,cn.com.servyou.work.api.model.agent.AgentEvent.builder().type(cn.com.servyou.work.api.constants.AgentEventType.RUN_STARTED).runId(run).seq(1).build());
            MessageRuntimeMetricEntity committed=metrics.selectOne(new LambdaQueryWrapper<MessageRuntimeMetricEntity>().eq(MessageRuntimeMetricEntity::getTenantId,"ZH000").eq(MessageRuntimeMetricEntity::getMessageId,id));
            committed.setRunStatus(cn.com.servyou.work.api.enums.RunStatusEnum.SUCCESS.getCode());committed.setEndTime(now);committed.setActiveMs(7139);committed.setTotalMs(7139);committed.setWallTotalMs(8000);committed.setLastSeq(7);
            assertEquals(1,metrics.completeFromTerminal(committed));
            collector.onEvent(id,cn.com.servyou.work.api.model.agent.AgentEvent.builder().type(cn.com.servyou.work.api.constants.AgentEventType.RUN_FINISHED).runId(run).seq(7)
                    .payload(cn.com.servyou.work.api.model.agent.RunFinishedPayload.builder().reason("completed").build()).build());
        }));
        org.mockito.ArgumentCaptor<MessageRuntimeMetricEntity> captured=org.mockito.ArgumentCaptor.forClass(MessageRuntimeMetricEntity.class);
        org.mockito.Mockito.verify(detector).detectCommitted(captured.capture());assertEquals(Integer.valueOf(7139),captured.getValue().getActiveMs());
        assertEquals("ZH000",captured.getValue().getTenantId());assertEquals(run,captured.getValue().getRunId());
    }
    private ImBotTakeover takeover(){return new ImBotTakeover(bots,sessions,messages,service,null,null,new StandardEnvironment(),tx);}
    private ImBotSessionEntity takeoverTarget(ImBotTakeover worker,cn.hutool.json.JSONObject c){
        return ReflectionTestUtils.invokeMethod(worker,"target",b,c,"takeover-chat-"+marker,SessionType.P2P.getCode(),"真人测试","node-a",b.getOwnerVersion());
    }
    private cn.hutool.json.JSONObject personMessage(String id,String sender,long time){
        return JSONUtil.createObj().set("id",id).set("sender",sender).set("time",time).set("type",Wire.TEXT.getCode()).set("senderType",Wire.USER.getCode()).set("text","测试正文");
    }
    @Test void takeoverRoutesOtherPeopleToOwnerAndPersistsDeduplicationInRealDatabase(){
        cn.hutool.json.JSONObject c=takeoverConfig();ImBotTakeover worker=takeover();ImBotSessionEntity s=takeoverTarget(worker,c);long time=System.currentTimeMillis()-1000;
        cn.hutool.json.JSONArray input=new cn.hutool.json.JSONArray();input.add(personMessage("history","other-person",c.getLong("startedAt")-1000));input.add(personMessage("new-message","other-person",time));
        for(int i=0;i<2;i++)ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        List<ImBotMessageEntity> rows=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(1,rows.size());assertEquals(userId,rows.get(0).getUserId());assertEquals("other-person",ImBotTakeoverConfig.context(rows.get(0)).getStr("externalUserId"));
        assertTrue(ImBotTakeoverConfig.user(rows.get(0)));assertEquals(Process.QUEUED.getCode(),rows.get(0).getProcessState());
        assertTrue(worker.matchesCurrent(b,rows.get(0)));
        ImBotEntity changed=new ImBotEntity();org.springframework.beans.BeanUtils.copyProperties(b,changed);
        changed.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.parseObj(c.toString()).set("generation","different-generation"),new StandardEnvironment()));
        assertFalse(worker.matchesCurrent(changed,rows.get(0)),"重新启用后的配置不得恢复旧代次输入");
        assertEquals(0,users.selectCount(new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getExternalUserId,"other-person")).intValue());
        assertFalse(service.sendable(b,normalBotSession(s)),"真人模式不能向旧机器人目标发送");
        assertEquals(s.getId(),worker.nextBatch(b.getId(),userId,s.getId()).get(0).getId(),"少量会话到达分页末尾后不能空转一个扫描周期");
    }
    private ImBotSessionEntity normalBotSession(ImBotSessionEntity s){ImBotSessionEntity other=new ImBotSessionEntity();org.springframework.beans.BeanUtils.copyProperties(s,other);other.setChatKey(ImBotServiceImpl.hash(s.getExternalChatId()));return other;}
    @Test void wecomMcpSelfMessagesAreSkippedAndManualRepliesCancelPendingInput(){
        cn.hutool.json.JSONObject c=takeoverConfig();c.set("messageUserId","mcp-self");b.setPlatform(Platform.WECOM.getCode());
        b.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(c,new StandardEnvironment()));bots.updateById(b);
        ImBotTakeover worker=takeover();ImBotSessionEntity s=takeoverTarget(worker,c);long time=System.currentTimeMillis()-3000;
        cn.hutool.json.JSONArray input=new cn.hutool.json.JSONArray();
        input.add(personMessage("earlier-self","mcp-self",time-1000));input.add(personMessage("incoming","other-person",time));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        List<ImBotMessageEntity> saved=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(1,saved.size());assertEquals("incoming",saved.get(0).getExternalMessageId());
        input.add(personMessage("manual-reply","mcp-self",time+1000));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        assertEquals(Process.CANCELED.getCode(),messages.selectById(saved.get(0).getId()).getProcessState());
        assertEquals(Send.CANCELED.getCode(),messages.selectById(saved.get(0).getId()).getSendState());
        assertEquals(1,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        assertEquals("takeover-self",users.selectOne(new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,b.getId())).getExternalUserId());
    }
    @Test void wecomConfigurationPreservesCallbackBindingAndStoresSeparateMcpIdentity(){
        own();authorize("callback-self");b.setPlatform(Platform.WECOM.getCode());bots.updateById(b);
        ImBotTakeoverServiceImpl config=new ImBotTakeoverServiceImpl(service,bots,new StandardEnvironment());
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotTakeoverRequestDTO r=new ImBotTakeoverRequestDTO();r.setBotKey(b.getId());r.setExpectedConfigVersion(b.getConfigVersion());
            r.setIdentityMode(Identity.USER.getCode());r.setOwnerUserId(userId);r.setExternalUserId("mcp-self");r.setMessageUserId("mcp-self");
            assertThrows(BizException.class,()->config.configure(r));
            r.setExternalUserId("callback-self");r.setMessageUserId(null);assertThrows(BizException.class,()->config.configure(r));
            r.setMessageUserId("mcp-self");ImBotTakeoverDTO saved=config.configure(r);
            assertEquals("callback-self",saved.getExternalUserId());assertEquals("mcp-self",saved.getMessageUserId());assertFalse(saved.getEnabled());
            assertEquals("callback-self",users.selectOne(new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,b.getId())).getExternalUserId());
            ImBotEntity stored=bots.selectById(b.getId());assertTrue(stored.getTakeoverConfigCipher().startsWith("ENC("));
            cn.hutool.json.JSONObject content=ImBotTakeoverConfig.read(stored,new StandardEnvironment());
            assertEquals("callback-self",content.getStr("externalUserId"));assertEquals("mcp-self",content.getStr("messageUserId"));
            AdminTenantContext.clear();
        });
    }
    @Test void takeoverManualReplyCancelsRunningAndPendingFinalWithoutReopening(){
        cn.hutool.json.JSONObject c=takeoverConfig();ImBotTakeover worker=takeover();ImBotSessionEntity s=takeoverTarget(worker,c);long time=System.currentTimeMillis()-1000;
        cn.hutool.json.JSONArray input=new cn.hutool.json.JSONArray();input.add(personMessage("to-reply","other-person",time));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        ImBotMessageEntity m=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(1,messages.transition(m.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RUNNING.getCode(),null));
        m.setProcessState(Process.SUCCEEDED.getCode());m.setReplyContent("待发送成果");assertEquals(1,messages.publish(m,"node-a",b.getOwnerVersion()));
        input.add(personMessage("manual-reply","takeover-self",time+100));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        ImBotMessageEntity canceled=messages.selectById(m.getId());assertEquals(Process.CANCELED.getCode(),canceled.getProcessState());assertEquals(Send.CANCELED.getCode(),canceled.getSendState());
        assertEquals(0,messages.publish(m,"node-a",b.getOwnerVersion()),"迟到模型结果不得重新排队");
        assertEquals(0,messages.selectDispatch(b.getId(),100).size());
    }
    @Test void takeoverSuccessfulReplyKeepsNewInputArrivingDuringExecution(){
        cn.hutool.json.JSONObject c=takeoverConfig();ImBotTakeover worker=takeover();ImBotSessionEntity s=takeoverTarget(worker,c);long time=System.currentTimeMillis()-2000;
        cn.hutool.json.JSONArray input=new cn.hutool.json.JSONArray();input.add(personMessage("one","other-person",time));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        ImBotMessageEntity m=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        messages.transition(m.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RUNNING.getCode(),null);
        m.setProcessState(Process.SUCCEEDED.getCode());m.setReplyContent("已回复");messages.publish(m,"node-a",b.getOwnerVersion());
        m=messages.selectById(m.getId());assertEquals(1,messages.acknowledge(m,Send.ACCEPTED.getCode(),"provider-result",null,"node-a",b.getOwnerVersion()));
        input.add(personMessage("older-two","other-person",time-100));
        cn.hutool.json.JSONObject followup=personMessage("new-followup","other-person",time+100);followup.set("text","总结一下");input.add(followup);
        cn.hutool.json.JSONObject autoReply=personMessage("automatic-reply","takeover-self",System.currentTimeMillis());autoReply.set("text","已回复");input.add(autoReply);
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,s,input,"node-a",b.getOwnerVersion());
        assertEquals(2,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        assertEquals(Process.QUEUED.getCode(),messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getExternalMessageId,"new-followup")).getProcessState());
    }
    @Test void takeoverAttachmentsAreEncryptedAndFollowupIncludesOnlySavedFilesFromThisChat(){
        cn.hutool.json.JSONObject c=takeoverConfig();ImBotTakeover worker=takeover();ImBotSessionEntity target=takeoverTarget(worker,c);long time=System.currentTimeMillis()-5000;
        cn.hutool.json.JSONArray input=new cn.hutool.json.JSONArray();
        String[] types={Wire.FILE.getCode(),Wire.IMAGE.getCode(),Wire.VOICE.getCode()},names={"明细.xlsx","截图.jpeg","录音.raw"};
        for(int i=0;i<types.length;i++)input.add(personMessage("media-"+i,"other-person",time+i*100)
                .set("type",types[i]).set("text","").set("file",JSONUtil.createObj().set("media_id","private-media-"+i).set("file_name",names[i])).set("fileCount",1));
        for(int round=0;round<2;round++)ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,target,input,"node-a",b.getOwnerVersion());
        List<ImBotMessageEntity> media=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).orderByAsc(ImBotMessageEntity::getId));
        assertEquals(3,media.size());
        for(int i=0;i<media.size();i++) {
            ImBotMessageEntity row=media.get(i);assertEquals(Content.FILE.getCode(),row.getContentType());assertNull(row.getFileId());
            cn.hutool.json.JSONObject context=ImBotTakeoverConfig.context(row);assertFalse(row.getReplyContext().contains("private-media-"));
            assertTrue(AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(new StandardEnvironment()),context.getStr("file")).contains("private-media-"+i));
            context.remove("file");assertEquals(0,messages.attachFile(row.getId(),700L+i,names[i],context.toString(),"wrong-node",b.getOwnerVersion()));
            assertEquals(1,messages.attachFile(row.getId(),700L+i,names[i],context.toString(),"node-a",b.getOwnerVersion()));
        }
        input.add(personMessage("followup-with-files","other-person",time+1000).set("text","总结一下"));
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,target,input,"node-a",b.getOwnerVersion());
        ImBotMessageEntity followup=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getExternalMessageId,"followup-with-files"));
        // 另一会话附件以及时间上晚于本轮的附件都不进入当前输入。
        ImBotSessionEntity other=ReflectionTestUtils.invokeMethod(worker,"target",b,c,"another-chat-"+marker,SessionType.P2P.getCode(),"其他私聊","node-a",b.getOwnerVersion());
        cn.hutool.json.JSONArray future=new cn.hutool.json.JSONArray().put(personMessage("future-file","other-person",time+2000).set("text","")
                .set("file",JSONUtil.createObj().set("media_id","future-media").set("file_name","不能串入.xlsx")));
        for(ImBotSessionEntity chat:Arrays.asList(target,other)) {
            ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,chat,future,"node-a",b.getOwnerVersion());
            ImBotMessageEntity row=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getTargetSessionId,chat.getId()).eq(ImBotMessageEntity::getExternalMessageId,"future-file"));
            assertEquals(1,messages.attachFile(row.getId(),999L,"不能串入.xlsx","{}","node-a",b.getOwnerVersion()));
        }
        cn.com.servyou.work.api.interfaces.conversation.IUserEnvVarService vars=org.mockito.Mockito.mock(cn.com.servyou.work.api.interfaces.conversation.IUserEnvVarService.class);
        org.mockito.Mockito.when(vars.getRuntimeEnv(b.getTenantId(),userId)).thenReturn(new HashMap<>());
        cn.com.servyou.work.core.manager.runtimeenv.IRuntimeEnvCookieManager cookieManager=org.mockito.Mockito.mock(cn.com.servyou.work.core.manager.runtimeenv.IRuntimeEnvCookieManager.class);
        ImBotExecution executor=new ImBotExecution(service,messages,null,null,null,null,null,vars,cookieManager,new cn.com.servyou.work.core.agent.opencode.OpencodeProperties(),null,tx);
        JSONObject untrustedContext=JSONUtil.parseObj(followup.getReplyContext());
        untrustedContext.set("botKey","untrusted-bot").set("targetSessionKey","untrusted-target");followup.setReplyContext(untrustedContext.toString());
        asUser(()->{
            cn.com.servyou.work.api.model.conversation.ReqAgentChatDTO request=ReflectionTestUtils.invokeMethod(executor,"request",followup);
            List<cn.com.servyou.work.api.model.conversation.MessageSegment> segments=request.getInput();assertEquals(4,segments.size());
            for(int i=0;i<3;i++){assertEquals(String.valueOf(700+i),segments.get(i).getId());assertEquals(names[i],segments.get(i).getName());}
            assertTrue(segments.get(3).getValue().contains("总结一下"));assertTrue(segments.get(3).getValue().contains("代接管账号"));
            assertFalse(segments.get(3).getValue().contains("private-media-"));
            String text=segments.get(3).getValue();JSONObject routing=JSONUtil.parseObj(text.substring(text.indexOf('{'),text.indexOf('\n')));
            assertEquals(String.valueOf(b.getId()),routing.getStr("botKey"));assertEquals(b.getPlatform(),routing.getStr("platform"));
            assertEquals(String.valueOf(target.getId()),routing.getStr("targetSessionKey"));assertEquals(followup.getSessionId(),routing.getStr("sessionId"));
            assertEquals(Identity.USER.getCode(),routing.getStr("identityMode"));
        });
        ConversationFileMapper generated=org.mockito.Mockito.mock(ConversationFileMapper.class);
        ImBotFiles fileService=new ImBotFiles(service,messages,sessions,generated,null,null,null,tx,new StandardEnvironment(),null);
        assertEquals(1,messages.transition(followup.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RUNNING.getCode(),null));
        followup.setProcessState(Process.SUCCEEDED.getCode());followup.setReplyContent("附件总结正文");followup.setDbMessageId(123L);
        fileService.complete(followup,"node-a",b.getOwnerVersion());
        assertEquals(Send.PENDING.getCode(),messages.selectById(followup.getId()).getSendState());
        assertEquals(0,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getDirection,Direction.OUTBOUND.getCode())).intValue());
        org.mockito.Mockito.verifyNoInteractions(generated);
    }
    @Test void botTaskRoutingUsesPersistedPlatformAndCurrentChatForBothProviders(){
        own();authorize("routing-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("route-request","routing-user","生成云文档并发回当前聊天"));
        ImBotMessageEntity source=messages.selectDispatch(b.getId(),10).get(0);
        cn.com.servyou.work.api.interfaces.conversation.IUserEnvVarService vars=org.mockito.Mockito.mock(cn.com.servyou.work.api.interfaces.conversation.IUserEnvVarService.class);
        org.mockito.Mockito.when(vars.getRuntimeEnv(b.getTenantId(),userId)).thenReturn(new HashMap<>());
        ImBotExecution executor=new ImBotExecution(service,messages,null,null,null,null,null,vars,
                org.mockito.Mockito.mock(cn.com.servyou.work.core.manager.runtimeenv.IRuntimeEnvCookieManager.class),new cn.com.servyou.work.core.agent.opencode.OpencodeProperties(),null,tx);
        for(Platform platform:Platform.values()) {
            b.setPlatform(platform.getCode());bots.updateById(b);
            asUser(()->{
                cn.com.servyou.work.api.model.conversation.ReqAgentChatDTO request=ReflectionTestUtils.invokeMethod(executor,"request",source);
                String text=request.getInput().get(0).getValue();JSONObject routing=JSONUtil.parseObj(text.substring(text.indexOf('{'),text.indexOf('\n')));
                assertEquals(String.valueOf(b.getId()),routing.getStr("botKey"));assertEquals(platform.getCode(),routing.getStr("platform"));
                assertEquals(Identity.BOT.getCode(),routing.getStr("identityMode"));assertEquals(String.valueOf(source.getTargetSessionId()),routing.getStr("targetSessionKey"));
                assertEquals(source.getSessionId(),routing.getStr("sessionId"));assertNull(request.getAgent());
            });
        }
    }

    @Test void takeoverWaitsOnlyForActualPermissionEventsAndLogsTheOwnerSession()throws Exception {
        JSONObject c=takeoverConfig();ImBotTakeover worker=takeover();ImBotSessionEntity target=takeoverTarget(worker,c);
        ReflectionTestUtils.invokeMethod(worker,"ingest",b,c,target,new JSONArray().put(personMessage("permission-request","other-person",System.currentTimeMillis()-1000)),"node-a",b.getOwnerVersion());
        ImBotMessageEntity source=messages.selectDispatch(b.getId(),10).get(0);
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RUNNING.getCode(),null));source.setProcessState(Process.RUNNING.getCode());
        ImBotExecution executor=new ImBotExecution(service,messages,null,null,null,null,null,null,null,null,null,tx);
        Class<?> observing=Class.forName(ImBotExecution.class.getName()+"$Observation");
        java.lang.reflect.Constructor<?> constructor=observing.getDeclaredConstructor(ImBotExecution.class,ImBotMessageEntity.class,String.class,Long.class,java.util.function.BooleanSupplier.class);
        constructor.setAccessible(true);
        cn.com.servyou.work.api.interfaces.agent.AgentEventListener listener=(cn.com.servyou.work.api.interfaces.agent.AgentEventListener)constructor.newInstance(executor,source,"node-a",b.getOwnerVersion(),(java.util.function.BooleanSupplier)()->true);
        listener.accept(cn.com.servyou.work.api.model.agent.AgentEvent.builder().type(cn.com.servyou.work.api.constants.AgentEventType.TEXT).requestId(source.getRequestId()).seq(1)
                .payload(Collections.singletonMap("text","需我方确认后推送")).build());
        assertEquals(Process.RUNNING.getCode(),messages.selectById(source.getId()).getProcessState());
        Logger logger=(Logger)LoggerFactory.getLogger(ImBotExecution.class);ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            listener.accept(cn.com.servyou.work.api.model.agent.AgentEvent.builder().type(cn.com.servyou.work.api.constants.AgentEventType.PERMISSION_REQUEST).requestId(source.getRequestId())
                    .dbMessageId("987").runId("permission-run").seq(2).payload(Collections.singletonMap("requestId","actual-permission")).build());
            ImBotMessageEntity waiting=messages.selectById(source.getId());assertEquals(Process.WAITING_INPUT.getCode(),waiting.getProcessState());assertNull(waiting.getReplyContent());
            assertEquals(1,logs.list.size());String diagnostic=logs.list.get(0).getFormattedMessage();
            assertTrue(diagnostic.contains("userId="+userId));assertTrue(diagnostic.contains("sessionId="+source.getSessionId()));assertTrue(diagnostic.contains("botKey="+b.getId()));
        } finally {logger.detachAppender(logs);logs.stop();}
    }

    @Test void takeoverCheckpointsAndPauseUseRealConfigVersionAndOwnerFencing(){
        cn.hutool.json.JSONObject c=takeoverConfig();ImBotSessionEntity s=takeoverTarget(takeover(),c);s.setTakeoverState("{\"until\":123}");
        assertEquals(0,sessions.takeoverCheckpoint(s,b,"old-node",b.getOwnerVersion()));assertEquals(1,sessions.takeoverCheckpoint(s,b,"node-a",b.getOwnerVersion()));
        String previous=b.getTakeoverConfigCipher(),cipher=ImBotTakeoverConfig.encrypt(c.set("accessToken","test-only-private-token"),new StandardEnvironment());
        assertEquals(0,bots.takeoverCheckpoint(b,"old-node",b.getOwnerVersion(),previous,cipher));
        assertEquals(1,bots.takeoverCheckpoint(b,"node-a",b.getOwnerVersion(),previous,cipher));assertEquals(0,bots.takeoverCheckpoint(b,"node-a",b.getOwnerVersion(),previous,cipher));
        assertFalse(bots.selectById(b.getId()).getTakeoverConfigCipher().contains("test-only-private-token"));
        ImBotEntity stale=new ImBotEntity();org.springframework.beans.BeanUtils.copyProperties(b,stale);stale.setConfigVersion(b.getConfigVersion()+1);
        assertEquals(0,bots.pauseTakeover(stale,"node-a",b.getOwnerVersion(),"不应覆盖"));assertEquals(1,bots.pauseTakeover(b,"node-a",b.getOwnerVersion(),"测试暂停"));
        assertEquals(0,sessions.takeoverCheckpoint(s,b,"node-a",b.getOwnerVersion()));assertEquals(0,bots.selectById(b.getId()).getEnabled().intValue());
    }
    @Test void takeoverConfigurationAndOAuthStateRemainOwnerBoundEncryptedAndVersioned(){
        own();authorize("takeover-self");ImBotTakeoverServiceImpl config=new ImBotTakeoverServiceImpl(service,bots,new StandardEnvironment());
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotTakeoverRequestDTO r=new ImBotTakeoverRequestDTO();r.setBotKey(b.getId());r.setExpectedConfigVersion(b.getConfigVersion());r.setIdentityMode(Identity.USER.getCode());
            r.setOwnerUserId(userId);r.setExternalUserId("different-person");assertThrows(BizException.class,()->config.configure(r));
            r.setExternalUserId("takeover-self");r.setPrivateChatIds(Collections.singletonList("private-chat"));r.setDiscoverEnabled(true);
            ImBotTakeoverDTO updated=config.configure(r);assertFalse(updated.getEnabled());assertFalse(updated.getAuthorizationReady());
            assertThrows(BizException.class,()->config.configure(r));
            r.setExpectedConfigVersion(updated.getConfigVersion());r.setRedirectUri("http://example.com/callback");assertThrows(BizException.class,()->config.startAuthorization(r));
            r.setRedirectUri("https://example.com/callback");ImBotTakeoverDTO started=config.startAuthorization(r);
            assertTrue(started.getAuthorizationUrl().contains("search%3Amessage"));
            String state=okhttp3.HttpUrl.get(started.getAuthorizationUrl()).queryParameter("state");
            String cipher=bots.selectById(b.getId()).getTakeoverConfigCipher();assertFalse(cipher.contains(state));assertTrue(cipher.startsWith("ENC("));
            r.setExpectedConfigVersion(started.getConfigVersion());r.setState("wrong-state");r.setCode("test-code");assertThrows(BizException.class,()->config.completeAuthorization(r));
            assertEquals(started.getConfigVersion(),bots.selectById(b.getId()).getConfigVersion());
            assertFalse(JSONUtil.toJsonStr(config.status(b.getId(),false)).contains("oauthState"));AdminTenantContext.clear();
        });
        try(java.sql.Connection connection=data.getConnection();PreparedStatement check=connection.prepareStatement("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='sw_im_bot' AND COLUMN_NAME IN ('identity_mode','takeover_config_cipher')) OR (TABLE_NAME='sw_im_bot_session' AND COLUMN_NAME='takeover_state')) AND COLUMN_COMMENT<>''")){
            try(ResultSet rs=check.executeQuery()){assertTrue(rs.next());assertEquals(3,rs.getInt(1));}
        }catch(SQLException e){throw new AssertionError(e);}
    }
    @Test void concurrentOwnersAndExpiredOwnerAreFenced()throws Exception {
        isolateLeaseCompetition();
        assertEquals(1,bots.release(b.getId(),"imtest-reservation",0L));
        b.setEnabled(ImBotServiceImpl.flag(false));bots.updateById(b);
        assertEquals(1,bots.release(b.getId(),"imtest-reservation",0L));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            Future<Integer> a=pool.submit(()->{start.await();return bots.claim(b.getId(),"node-a",60);});Future<Integer> z=pool.submit(()->{start.await();return bots.claim(b.getId(),"node-b",60);});start.countDown();
            assertEquals(1,a.get()+z.get());ImBotEntity owner=bots.selectById(b.getId());assertEquals(1L,owner.getOwnerVersion().longValue());
            String old=owner.getOwnerNode();assertEquals(1,bots.renew(b.getId(),old,owner.getOwnerVersion(),60));
            try(java.sql.Connection c=data.getConnection();PreparedStatement st=c.prepareStatement("UPDATE sw_im_bot SET lease_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?")){st.setLong(1,b.getId());st.executeUpdate();}
            assertEquals(0,bots.renew(b.getId(),old,1L,60));assertEquals(1,bots.claim(b.getId(),"takeover",60));assertEquals(2L,bots.selectById(b.getId()).getOwnerVersion().longValue());
            assertEquals(0,bots.connection(b.getId(),old,1L,Connection.ONLINE.getCode(),null));assertEquals(0,bots.release(b.getId(),old,1L));
        } finally{pool.shutdownNow();}
    }
    @Test void duplicateMessageAndCaseSensitiveIdentityUseRealIndexes() {
        own();authorize("User-A");ImBotChannel.Inbound e=event("Message-A","User-A","你好");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),e);inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),e);
        assertEquals(1,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("message-a","User-A","大小写不同的消息"));
        Logger logger=(Logger)LoggerFactory.getLogger(ImBotInbox.class);ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("Message-B","user-a","大小写不同的用户未绑定"));
            assertEquals(1,logs.list.size());String diagnostic=logs.list.get(0).getFormattedMessage();
            assertTrue(diagnostic.contains("未授权或未绑定"));assertTrue(diagnostic.contains("externalUserId=user-a"));
            assertTrue(diagnostic.contains("externalAppId="+b.getExternalAppId()));assertTrue(diagnostic.contains("externalMessageId=Message-B"));
            assertFalse(diagnostic.contains("大小写不同的用户未绑定"));
        } finally {logger.detachAppender(logs);logs.stop();}
        assertEquals(2,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        assertDoesNotThrow(()->createBot(marker.toUpperCase(Locale.ROOT)));
    }
    @Test void fileMetadataIsEncryptedAndCheckpointHonorsOwnerAndAuthorization() {
        own();ImBotUserEntity user=authorize("file-user");
        ImBotChannel.Inbound file=event("file-inbound","file-user","分析文档.docx");file.setContentType(Content.FILE.getCode());
        String source=cn.hutool.json.JSONUtil.createObj().set("file_key","file-test-key").set("file_name","分析文档.docx").toString();
        file.setContext(cn.hutool.json.JSONUtil.createObj().set("file",cn.hutool.json.JSONUtil.parseObj(source)).toString());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),file);
        ImBotMessageEntity row=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(Content.FILE.getCode(),row.getContentType());assertNull(row.getFileId());
        assertFalse(row.getReplyContext().contains("file-test-key"));
        assertEquals(0,messages.attachFile(row.getId(),123L,"分析文档.docx","{}","old-node",b.getOwnerVersion()));
        users.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,user.getId()).set(ImBotUserEntity::getAuthorized,0));
        assertEquals(0,messages.attachFile(row.getId(),123L,"分析文档.docx","{}","node-a",b.getOwnerVersion()));
        users.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,user.getId()).set(ImBotUserEntity::getAuthorized,1));
        assertEquals(1,messages.attachFile(row.getId(),123L,"分析文档.docx","{}","node-a",b.getOwnerVersion()));
        assertEquals(0,messages.attachFile(row.getId(),124L,"其他.docx","{}","node-a",b.getOwnerVersion()));
        ImBotMessageEntity saved=messages.selectById(row.getId());assertEquals(123L,saved.getFileId().longValue());assertEquals("{}",saved.getReplyContext());
    }
    @Test void resultFilesReuseSendRecordsWithoutResettingReceiptsOrStartingNextRound() {
        own();authorize("result-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("first","result-user","生成文档"));
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        source.setProcessState(Process.SUCCEEDED.getCode());source.setDbMessageId(123L);source.setReplyContent("已生成文档");source.setSendState(Send.PENDING.getCode());assertEquals(1,messages.progress(source,"node-a",b.getOwnerVersion()));
        ImBotMessageEntity output=new ImBotMessageEntity();output.setId(IdWorker.getId());output.setFileId(456L);output.setContentType(Content.FILE.getCode());output.setContent("成果.pdf");output.setProviderUuid(UUID.randomUUID().toString());output.setRequestId("result-"+marker);output.setReplyContext("{}");
        assertEquals(0,messages.acceptResult(output,source.getId(),"stale",b.getOwnerVersion()));
        assertEquals(1,messages.acceptResult(output,source.getId(),"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.transition(output.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        messages.acceptResult(output,source.getId(),"node-a",b.getOwnerVersion());
        assertEquals(Send.SENDING.getCode(),messages.selectById(output.getId()).getSendState());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("next","result-user","分析下一个文档"));
        assertTrue(messages.selectDispatch(b.getId(),100).isEmpty(),"成果发送完毕前不覆盖同会话本轮文件");
        assertEquals(1,messages.transition(output.getId(),"node-a",b.getOwnerVersion(),null,Send.SENDING.getCode(),null,Send.ACCEPTED.getCode()));
        assertEquals(1,messages.selectDispatch(b.getId(),100).size());
    }
    @Test void postAttachmentKeepsUserInstructionAcrossFileCheckpointAndRejectsExtraAttachments() {
        own();authorize("post-user");
        ImBotChannel.Inbound post=event("post-with-file","post-user","门店销售.xlsx");post.setContentType(Content.FILE.getCode());
        post.setContext(cn.hutool.json.JSONUtil.createObj().set("prompt","整理门店汇总发我").set("fileCount",1)
                .set("file",cn.hutool.json.JSONUtil.createObj().set("file_key","file-post").set("file_name","门店销售.xlsx")).toString());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),post);
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(Process.QUEUED.getCode(),source.getProcessState());
        cn.hutool.json.JSONObject context=cn.hutool.json.JSONUtil.parseObj(source.getReplyContext());assertEquals("整理门店汇总发我",context.getStr("prompt"));
        assertFalse(source.getReplyContext().contains("file-post"));context.remove("file");
        assertEquals(1,messages.attachFile(source.getId(),888L,"门店销售.xlsx",context.toString(),"node-a",b.getOwnerVersion()));
        assertEquals("整理门店汇总发我",cn.hutool.json.JSONUtil.parseObj(messages.selectById(source.getId()).getReplyContext()).getStr("prompt"));
        post.setMessageId("post-many-files");
        context=cn.hutool.json.JSONUtil.parseObj(post.getContext());context.set("fileCount",2);post.setContext(context.toString());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),post);
        ImBotMessageEntity rejected=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).ne(ImBotMessageEntity::getId,source.getId()));
        assertEquals(Process.FAILED.getCode(),rejected.getProcessState());assertEquals(Send.PENDING.getCode(),rejected.getSendState());
        assertTrue(rejected.getReplyContent().contains("每次发送一个附件"));assertNull(rejected.getFileId());
    }
    @Test void streamingAcknowledgmentNeverRevertsNewerContentOrExecutionState() {
        own();authorize("stream-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("stream-root","stream-user","查天气"));
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        source.setProcessState(Process.RUNNING.getCode());source.setLastSeq(1);source.setDbMessageId(123L);source.setRunId("run-stream");source.setReplyContent("正在思考");
        source.setReplyContext("{\"streamClosed\":false}");assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        ImBotMessageEntity inFlight=messages.selectById(source.getId());
        source.setLastSeq(2);source.setProcessState(Process.WAITING_INPUT.getCode());source.setReplyContent("请选择城市：1.杭州 2.上海");source.setReplyContext("{\"streamClosed\":true}");
        assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.acknowledge(inFlight,Send.ACCEPTED.getCode(),"om_card",null,"node-a",b.getOwnerVersion()));
        ImBotMessageEntity next=messages.selectById(source.getId());
        assertEquals(Process.WAITING_INPUT.getCode(),next.getProcessState());assertEquals(source.getReplyContent(),next.getReplyContent());
        assertEquals(Send.PENDING.getCode(),next.getSendState());assertEquals("om_card",next.getProviderMessageId());
        assertEquals(0,messages.checkpoint(inFlight,"node-a",b.getOwnerVersion()),"旧事件不能倒退观察状态");
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        next=messages.selectById(source.getId());
        source.setProcessState(Process.SUCCEEDED.getCode());assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.acknowledge(next,Send.ACCEPTED.getCode(),null,null,"node-a",b.getOwnerVersion()));
        ImBotMessageEntity complete=messages.selectById(source.getId());
        assertEquals(Process.SUCCEEDED.getCode(),complete.getProcessState());assertEquals(Send.PENDING.getCode(),complete.getSendState());
        assertEquals("om_card",complete.getProviderMessageId(),"无消息标识的更新回执保留原卡片标识");
        assertEquals(0,messages.acknowledge(next,Send.ACCEPTED.getCode(),"stale",null,"old-node",b.getOwnerVersion()));
    }
    @Test void unknownDeliveryAndCancellationAreNotReopenedByStreamingProjection() {
        own();authorize("stream-unknown");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("unknown-root","stream-unknown","任务"));
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        source.setProcessState(Process.RUNNING.getCode());source.setLastSeq(1);source.setReplyContent("片段一");
        assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        ImBotMessageEntity sending=messages.selectById(source.getId());
        assertEquals(1,messages.acknowledge(sending,Send.UNKNOWN.getCode(),null,"网络结果未知","node-a",b.getOwnerVersion()));
        source.setLastSeq(2);source.setReplyContent("片段二");assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(Send.UNKNOWN.getCode(),messages.selectById(source.getId()).getSendState());
        assertEquals("网络结果未知",messages.selectById(source.getId()).getLastError());
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.RUNNING.getCode(),null,Process.CANCEL_REQUESTED.getCode(),Send.CANCELED.getCode()));
        assertEquals(0,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(Send.CANCELED.getCode(),messages.selectById(source.getId()).getSendState());
    }
    @Test void streamingComparisonUsesOrdinaryEqualityAndNullChecksThroughProductionInterceptors() {
        own();authorize("content-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("content-root","content-user","检查正文变化"));
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        source.setProcessState(Process.RUNNING.getCode());
        assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertNull(messages.selectById(source.getId()).getSendState(),"空正文不能创建发送任务");
        source.setReplyContent("Abc");assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(Send.PENDING.getCode(),messages.selectById(source.getId()).getSendState(),"NULL 到正文必须识别为变化");
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        assertEquals(1,messages.acknowledge(messages.selectById(source.getId()),Send.ACCEPTED.getCode(),"om_content",null,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(Send.ACCEPTED.getCode(),messages.selectById(source.getId()).getSendState(),"相同正文不能重复排队");
        source.setReplyContent("新的正文");assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(Send.PENDING.getCode(),messages.selectById(source.getId()).getSendState(),"正文变化必须重新排队");
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        ImBotMessageEntity inFlight=messages.selectById(source.getId());
        source.setReplyContent("新的正文继续");assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.acknowledge(inFlight,Send.ACCEPTED.getCode(),null,null,"node-a",b.getOwnerVersion()));
        assertEquals(Send.PENDING.getCode(),messages.selectById(source.getId()).getSendState(),"旧回执不能吞掉新正文");
    }
    @Test void questionAnswerIsConsumedOnlyOnceForTheSameUserAndChat() {
        own();authorize("answer-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("question-root","answer-user","查天气"));
        ImBotMessageEntity source=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        source.setProcessState(Process.WAITING_INPUT.getCode());source.setLastSeq(1);source.setDbMessageId(987L);source.setRunId("run-question");source.setReplyContent("请选择城市");
        assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("question-answer","answer-user","1"));
        ImBotMessageEntity answer=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).ne(ImBotMessageEntity::getId,source.getId()));
        assertEquals(0,messages.consumeAnswer(answer.getId(),source.getId(),"old-node",b.getOwnerVersion()));
        Long target=answer.getTargetSessionId();answer.setTargetSessionId(target+1);messages.updateById(answer);
        assertEquals(0,messages.consumeAnswer(answer.getId(),source.getId(),"node-a",b.getOwnerVersion()),"不能串到其他聊天");
        answer.setTargetSessionId(target);answer.setUserId(userId+"-other");messages.updateById(answer);
        assertEquals(0,messages.consumeAnswer(answer.getId(),source.getId(),"node-a",b.getOwnerVersion()),"不能消费其他用户回答");
        answer.setUserId(userId);messages.updateById(answer);
        source.setReplyContext("{\"question\":{\"requestId\":\"que_test\",\"answers\":[[\"杭州\"]],\"submission\":\"SENDING\"}}");
        tx.executeWithoutResult(status->{assertEquals(1,messages.consumeAnswer(answer.getId(),source.getId(),"node-a",b.getOwnerVersion()));assertEquals(1,messages.publish(source,"node-a",b.getOwnerVersion()));});
        assertEquals(0,messages.consumeAnswer(answer.getId(),source.getId(),"node-a",b.getOwnerVersion()));
        ImBotMessageEntity consumed=messages.selectById(answer.getId());assertEquals(Process.SUCCEEDED.getCode(),consumed.getProcessState());
        assertEquals(source.getDbMessageId(),consumed.getDbMessageId());assertEquals(source.getRunId(),consumed.getRunId());assertNull(consumed.getSendState());
        assertTrue(consumed.getReplyContext().contains(String.valueOf(source.getId())));
        assertTrue(messages.selectById(source.getId()).getReplyContext().contains("杭州"));
        assertEquals(source.getId(),messages.selectDispatch(b.getId(),100).get(0).getId());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("question-answer-2","answer-user","上海"));
        ImBotMessageEntity other=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getProcessState,Process.QUEUED.getCode()));
        users.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,b.getId()).set(ImBotUserEntity::getAuthorized,ImBotServiceImpl.flag(false)));
        assertEquals(0,messages.consumeAnswer(other.getId(),source.getId(),"node-a",b.getOwnerVersion()),"撤权后不能消费问题回答");
    }
    @Test void databaseConfiguredExternalIdentityRoutesToDifferentWorkbenchUser() throws Exception {
        own();ImBotUserEntity u=authorize(null);String external="ou_"+marker;
        assertNotEquals(userId,external);
        try(java.sql.Connection c=data.getConnection();PreparedStatement st=c.prepareStatement(
                "UPDATE sw_im_bot_user SET external_user_id=?,external_user_key=SHA2(?,256),bound_at=CURRENT_TIMESTAMP(6),binding_code_hash=NULL,binding_code_expires_at=NULL WHERE id=?")) {
            st.setString(1,external);st.setString(2,external);st.setLong(3,u.getId());assertEquals(1,st.executeUpdate());
        }
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("configured-identity",external,"数据库映射验证"));
        ImBotMessageEntity accepted=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertNotNull(accepted);assertEquals(userId,accepted.getUserId());assertEquals(b.getTenantId(),accepted.getTenantId());
        assertEquals(Process.QUEUED.getCode(),accepted.getProcessState());
        users.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotUserEntity>()
                .eq(ImBotUserEntity::getId,u.getId()).set(ImBotUserEntity::getAuthorized,ImBotServiceImpl.flag(false)));
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("revoked-identity",external,"撤权后不能接收业务"));
        assertEquals(1,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
    }
    @Test void bindingCodeConsumedOnceAndGroupDiscoveryDoesNotRunModel() {
        own();ImBotUserEntity u=authorize(null);String code="ABCDEF0123456789";u.setBindingCodeHash(ImBotServiceImpl.hash(code));u.setBindingCodeExpiresAt(bots.databaseNow().plusMinutes(5));users.updateById(u);
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("bind-1","open-a","绑定 "+code));
        ImBotUserEntity bound=users.selectById(u.getId());assertEquals("open-a",bound.getExternalUserId());assertNull(bound.getBindingCodeHash());
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("bind-2","open-b","绑定 "+code));assertEquals("open-a",users.selectById(u.getId()).getExternalUserId());
        ImBotChannel.Inbound group=event("group-1","open-a","@机器人 问题");group.setChatId("unknown-group");group.setSessionType(SessionType.GROUP.getCode());inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),group);
        assertEquals(2,sessions.selectCount(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,b.getId())).intValue());
        assertEquals(1,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        ImBotMessageEntity control=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));assertFalse(control.getContent().contains(code));assertEquals(Send.PENDING.getCode(),control.getSendState());
    }
    @Test void dingTalkCallbacksBindAndDiscoverGroupsThroughRealMySql() {
        own();
        b.setPlatform(Platform.DINGTALK.getCode());
        bots.updateById(b);
        ImBotUserEntity pending = authorize(null);
        String code = "ABCDEF0123456789";
        pending.setBindingCodeHash(ImBotServiceImpl.hash(code));
        pending.setBindingCodeExpiresAt(bots.databaseNow().plusMinutes(5));
        users.updateById(pending);

        cn.hutool.json.JSONObject data = JSONUtil.createObj().set("msgtype", "text")
                .set("robotCode", b.getExternalAppId()).set("senderStaffId", "ding-member")
                .set("conversationType", "1").set("msgId", "ding-bind")
                .set("text", JSONUtil.createObj().set("content", "绑定 " + code));
        inbox.accept(b.getId(), "node-a", b.getOwnerVersion(), DingTalkBotChannel.inbound(data, b.getExternalAppId()));
        assertEquals("ding-member", users.selectById(pending.getId()).getExternalUserId());
        assertNull(users.selectById(pending.getId()).getBindingCodeHash());

        data.set("msgId", "ding-question").set("text", JSONUtil.createObj().set("content", "你好"));
        ImBotChannel.Inbound direct = DingTalkBotChannel.inbound(data, b.getExternalAppId());
        inbox.accept(b.getId(), "node-a", b.getOwnerVersion(), direct);
        inbox.accept(b.getId(), "node-a", b.getOwnerVersion(), direct);
        data.set("msgId", "ding-group").set("conversationType", "2")
                .set("conversationId", "cid-group").set("isInAtList", true);
        inbox.accept(b.getId(), "node-a", b.getOwnerVersion(), DingTalkBotChannel.inbound(data, b.getExternalAppId()));

        assertEquals(2, messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>()
                .eq(ImBotMessageEntity::getBotId, b.getId())).intValue());
        ImBotMessageEntity question = messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>()
                .eq(ImBotMessageEntity::getBotId, b.getId()).eq(ImBotMessageEntity::getExternalMessageId, "ding-question"));
        assertEquals(userId, question.getUserId());
        assertEquals(Process.QUEUED.getCode(), question.getProcessState());
        assertEquals("ding-member", sessions.selectById(question.getTargetSessionId()).getExternalChatId());
        assertEquals(1, sessions.selectCount(new LambdaQueryWrapper<ImBotSessionEntity>()
                .eq(ImBotSessionEntity::getBotId, b.getId()).eq(ImBotSessionEntity::getExternalChatId, "cid-group")).intValue());
    }

    @Test void sendingIdempotencyPaginationAndUnbindCancellation() {
        own();authorize("open-send");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("msg-send","open-send","你好"));
        ImBotSessionEntity target=sessions.selectOne(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,b.getId()));
        asUser(()->{
            ImBotSendDTO send=new ImBotSendDTO();send.setBotKey(b.getId());send.setTargetSessionKey(target.getId());send.setRequestId(marker);send.setContentType(Content.TEXT.getCode());send.setContent("测试正文");
            ImBotMessageDTO first=service.send(send);assertEquals(first.getMessageId(),service.send(send).getMessageId());send.setContent("冲突正文");assertThrows(Exception.class,()->service.send(send));
            ImBotQueryDTO query=new ImBotQueryDTO();query.setBotKey(b.getId());query.setPlatform(Platform.FEISHU.getCode());query.setPageSize(1);assertEquals(2,service.pageMessages(query,false).getTotal());assertEquals(1,service.pageBots(query,false).getTotal());
            service.unbind(b.getId());assertNull(service.user(b.getId(),userId).getExternalUserKey());assertEquals(Send.CANCELED.getCode(),messages.selectById(first.getMessageId()).getSendState());
            assertEquals(0,sessions.selectCount(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,b.getId())).intValue(),"解绑后不能复用旧外部身份的目标");
            assertEquals(Process.CANCELED.getCode(),messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getDirection,Direction.INBOUND.getCode())).getProcessState());
        });
    }
    @Test void lateCallbackCannotUndoCancellationAndOldOwnerCannotWrite()throws Exception {
        own();authorize("open-run");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("run-1","open-run","运行"));
        ImBotMessageEntity m=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId()));
        assertEquals(1,messages.transition(m.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RUNNING.getCode(),null));
        service.cancelPending(b.getId(),userId);m.setProcessState(Process.RUNNING.getCode());assertEquals(1,messages.progress(m,"node-a",b.getOwnerVersion()));assertEquals(Process.CANCEL_REQUESTED.getCode(),messages.selectById(m.getId()).getProcessState());
        m.setProcessState(Process.SUCCEEDED.getCode());m.setSendState(Send.PENDING.getCode());m.setReplyContent("迟到答复");assertEquals(1,messages.progress(m,"node-a",b.getOwnerVersion()));assertEquals(Process.CANCELED.getCode(),messages.selectById(m.getId()).getProcessState());assertEquals(Send.CANCELED.getCode(),messages.selectById(m.getId()).getSendState());
        bots.release(b.getId(),"node-a",b.getOwnerVersion());bots.claim(b.getId(),"node-b",60);assertEquals(0,messages.progress(m,"node-a",b.getOwnerVersion()));
        BizException failure=assertThrows(BizException.class,()->inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("old-owner","open-run","迟到入站")));
        assertEquals(Error.LEASE_LOST.getCode(),failure.getCode());
    }
    @Test void concurrentOwnersCompeteAndExpiredLeaseTransfersWithNewVersion()throws Exception {
        isolateLeaseCompetition();
        b.setEnabled(ImBotServiceImpl.flag(false));bots.updateById(b);
        assertEquals(1,bots.release(b.getId(),"imtest-reservation",0L));
        ExecutorService contenders=Executors.newFixedThreadPool(4);CountDownLatch start=new CountDownLatch(1);List<Future<Integer>> claims=new ArrayList<>();
        try {
            for(int i=0;i<4;i++){final String node="competing-"+i;claims.add(contenders.submit(()->{start.await();return bots.claim(b.getId(),node,60);}));}
            start.countDown();int winners=0;for(Future<Integer> claim:claims)winners+=claim.get(10,TimeUnit.SECONDS);assertEquals(1,winners);
            ImBotEntity first=bots.selectById(b.getId());
            try(java.sql.Connection c=data.getConnection();PreparedStatement st=c.prepareStatement("UPDATE sw_im_bot SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?")){st.setLong(1,b.getId());st.executeUpdate();}
            assertEquals(0,bots.renew(b.getId(),first.getOwnerNode(),first.getOwnerVersion(),60));assertEquals(1,bots.claim(b.getId(),"replacement",60));
            assertEquals(first.getOwnerVersion()+1,bots.selectById(b.getId()).getOwnerVersion().longValue());assertEquals(0,bots.release(b.getId(),first.getOwnerNode(),first.getOwnerVersion()));
        }finally{contenders.shutdownNow();}
    }
    @Test void dispatchBlocksSameSessionAndContinuesOtherSessions() {
        own();authorize("open-order");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("first","open-order","1"));
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("second","open-order","2"));
        ImBotChannel.Inbound other=event("other","open-order","3");other.setChatId("another-target");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),other);
        List<ImBotMessageEntity> first=messages.selectDispatch(b.getId(),100);assertEquals(2,first.size());
        ImBotMessageEntity oldest=first.get(0);assertEquals(1,messages.transition(oldest.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RECOVERING.getCode(),null));
        assertEquals(2,messages.selectDispatch(b.getId(),100).size());
        assertEquals(1,messages.transition(oldest.getId(),"node-a",b.getOwnerVersion(),Process.RECOVERING.getCode(),null,Process.SUCCEEDED.getCode(),null));
        assertEquals("second",messages.selectDispatch(b.getId(),100).get(0).getExternalMessageId());
    }
    @Test void failureFinalizationKeepsOwnerFencingAndCancellation() {
        own();authorize("failed-owner-user");
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("failed-owner","failed-owner-user","测试失败收尾"));
        ImBotMessageEntity source=messages.selectDispatch(b.getId(),100).get(0);
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RECOVERING.getCode(),null));
        ImBotExecution execution=new ImBotExecution(service,messages,null,null,null,null,null,null,null,null,null,tx);
        java.util.function.BooleanSupplier live=()->true;
        ReflectionTestUtils.invokeMethod(execution,"fail",source.getId(),"old-node",b.getOwnerVersion(),live,"失败收尾");
        assertEquals(Process.RECOVERING.getCode(),messages.selectById(source.getId()).getProcessState(),"非归属节点不能写失败终态");
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.RECOVERING.getCode(),null,Process.CANCEL_REQUESTED.getCode(),null));
        ReflectionTestUtils.invokeMethod(execution,"fail",source.getId(),"node-a",b.getOwnerVersion(),live,"停止时失败，结束机器人监测");
        ImBotMessageEntity canceled=messages.selectById(source.getId());
        assertEquals(Process.CANCELED.getCode(),canceled.getProcessState());assertEquals(Send.CANCELED.getCode(),canceled.getSendState());
        assertTrue(messages.selectDispatch(b.getId(),100).isEmpty());
    }
    @Test void missingConversationStopsRecoveryAndReleasesNextMessageWithoutRepeatedIdentityChecks() {
        own();authorize("recovery-user");
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("failed-recovery","recovery-user","分析文件"));
        inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("after-failure","recovery-user","新的问题"));
        ImBotMessageEntity source=messages.selectDispatch(b.getId(),100).get(0);
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RECOVERING.getCode(),null));
        source=messages.selectById(source.getId());
        // 仅身份作用域使用本测试账号；恢复服务和不存在会话的查询均执行真实代码及真实 MySQL。
        java.util.concurrent.atomic.AtomicInteger identityChecks=new java.util.concurrent.atomic.AtomicInteger();
        AccountCenterManagerImpl accounts=new AccountCenterManagerImpl(null,null,null,null,null,null,null,null,null,null,null,null,null) {
            @Override public void runAsPortalUser(String tenantId,String accountId,Runnable action) {
                identityChecks.incrementAndGet();assertEquals(b.getTenantId(),tenantId);assertEquals(userId,accountId);asUser(action);
            }
        };
        AiConversationMapper conversationMapper=sql.getMapper(AiConversationMapper.class);
        ConversationServiceImpl conversations=new ConversationServiceImpl(conversationMapper,null,null,null,null,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null,null,null,null);
        ReflectionTestUtils.setField(conversations,"projectSpaceAccessService",new ProjectSpaceAccessService(null,null,conversationMapper));
        ImBotExecution execution=new ImBotExecution(service,messages,null,null,null,conversations,accounts,null,null,null,null,tx);
        Logger logger=(Logger)LoggerFactory.getLogger(ImBotExecution.class);ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            execution.handle(b,"node-a",b.getOwnerVersion(),source,()->true);
            ImBotMessageEntity failed=messages.selectById(source.getId());
            assertEquals(Process.FAILED.getCode(),failed.getProcessState());assertEquals(Send.PENDING.getCode(),failed.getSendState());
            assertTrue(failed.getLastError().contains("停止自动监测"));assertTrue(cn.hutool.json.JSONUtil.parseObj(failed.getReplyContext()).getBool("streamClosed"));
            assertFalse(execution.observing(source.getId()));
            assertEquals("after-failure",messages.selectDispatch(b.getId(),100).get(0).getExternalMessageId());
            assertEquals(1,logs.list.size());String diagnostic=logs.list.get(0).getFormattedMessage();
            assertTrue(diagnostic.contains("NOT_FOUND"));assertTrue(diagnostic.contains("ProjectSpaceAccessService.requireConversation"));
            // 即使工作线程仍拿着旧 RECOVERING 快照，也不能再次验证身份、恢复或刷失败日志。
            execution.handle(b,"node-a",b.getOwnerVersion(),source,()->true);
            execution.handle(b,"node-a",b.getOwnerVersion(),source,()->true);
            assertEquals(1,identityChecks.get());assertEquals(1,logs.list.size());
            assertEquals(0,messages.checkpoint(source,"node-a",b.getOwnerVersion()),"迟到的运行检查点不能复活失败消息");
        } finally {logger.detachAppender(logs);logs.stop();execution.closeBot(b.getId());}
    }
    @Test void failureCannotBeWrittenByExpiredOwner() {
        own();authorize("stale-failure-user");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("stale-failure","stale-failure-user","问题"));
        ImBotMessageEntity source=messages.selectDispatch(b.getId(),100).get(0);
        assertEquals(1,messages.transition(source.getId(),"node-a",b.getOwnerVersion(),Process.QUEUED.getCode(),null,Process.RECOVERING.getCode(),null));
        ImBotExecution execution=new ImBotExecution(service,messages,null,null,null,null,null,null,null,null,null,tx);
        ReflectionTestUtils.invokeMethod(execution,"fail",source.getId(),"old-node",b.getOwnerVersion(),(java.util.function.BooleanSupplier)()->true,"停止监测");
        assertEquals(Process.RECOVERING.getCode(),messages.selectById(source.getId()).getProcessState());
        ReflectionTestUtils.invokeMethod(execution,"fail",source.getId(),"node-a",b.getOwnerVersion(),(java.util.function.BooleanSupplier)()->true,"停止监测");
        assertEquals(Process.FAILED.getCode(),messages.selectById(source.getId()).getProcessState());assertTrue(messages.selectDispatch(b.getId(),100).isEmpty());
    }
    @Test void lateTerminalCallbackCannotReturnSendingToPending() {
        own();authorize("open-send");inbox.accept(b.getId(),"node-a",b.getOwnerVersion(),event("receipt-race","open-send","问题"));
        ImBotMessageEntity row=messages.selectDispatch(b.getId(),1).get(0);
        row.setProcessState(Process.SUCCEEDED.getCode());row.setSendState(Send.PENDING.getCode());row.setReplyContent("答复");
        assertEquals(1,messages.progress(row,"node-a",b.getOwnerVersion()));
        assertEquals(1,messages.transition(row.getId(),"node-a",b.getOwnerVersion(),null,Send.PENDING.getCode(),null,Send.SENDING.getCode()));
        assertEquals(1,messages.progress(row,"node-a",b.getOwnerVersion()));assertEquals(Send.SENDING.getCode(),messages.selectById(row.getId()).getSendState());
        row.setSendState(Send.ACCEPTED.getCode());messages.progress(row,"node-a",b.getOwnerVersion());
        row.setSendState(Send.PENDING.getCode());assertEquals(0,messages.progress(row,"node-a",b.getOwnerVersion()));assertEquals(Send.ACCEPTED.getCode(),messages.selectById(row.getId()).getSendState());
    }
    @Test void freshBotSchemaHasNoExpertColumnAndRetainsChineseColumnComments() throws Exception {
        String ddl;
        try(InputStream in=new ClassPathResource("sql/upgrade/20261116.sql").getInputStream()) {
            ddl=IoUtil.readUtf8(in).replaceAll("(?m)^--.*$","").split(";")[0];
        }
        String table="im_bot_schema_"+UUID.randomUUID().toString().replace("-","");
        String temporary=ddl.replace("CREATE TABLE IF NOT EXISTS sw_im_bot (","CREATE TEMPORARY TABLE "+table+" (");
        assertNotEquals(ddl,temporary);
        try(java.sql.Connection c=data.getConnection();Statement st=c.createStatement()) {
            st.execute(temporary);
            try(ResultSet columns=st.executeQuery("SHOW FULL COLUMNS FROM "+table)) {
                while(columns.next()) {
                    assertNotEquals("default_agent_id",columns.getString("Field"));
                    assertFalse(columns.getString("Comment").trim().isEmpty(),columns.getString("Field")+"必须有中文注释");
                }
            }
        }
    }

    @Test void wecomAllowsOnlyOneEnabledIdentityEvenWhenTwoProfilesRace() throws Exception {
        b.setPlatform(Platform.WECOM.getCode());
        bots.updateById(b);
        ImBotEntity first = wecomTakeoverProfile(userId, "first");
        ImBotEntity second = wecomTakeoverProfile(userId + "-peer", "second");
        asBotAdmin(() -> {
            assertEquals(b.getId(), service.detail(first.getId(), false).getActiveBotKey());
            assertEquals(Identity.BOT.getCode(), service.detail(first.getId(), false).getActiveIdentityMode());
            assertWecomConflict(first);
            service.enable(botOperation(b), false);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> requests = new ArrayList<>();
            for (ImBotEntity profile : Arrays.asList(first, second)) {
                requests.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    asBotAdmin(() -> {
                        try {
                            service.enable(botOperation(profile), true);
                        } catch (BizException conflict) {
                            assertEquals(ImBotEnums.Error.WECOM_IDENTITY_CONFLICT.getCode(), conflict.getCode());
                        }
                    });
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> request : requests) request.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<ImBotEntity> enabled = service.family(b).stream()
                .filter(config -> ImBotServiceImpl.yes(config.getEnabled())).collect(java.util.stream.Collectors.toList());
        assertEquals(1, enabled.size());
        ImBotEntity winner = enabled.get(0);
        assertEquals(Identity.USER.getCode(), winner.getIdentityMode());
        asBotAdmin(() -> {
            ImBotDTO status = service.detail(b.getId(), false);
            assertEquals(winner.getId(), status.getActiveBotKey());
            assertEquals(winner.getConfigVersion(), status.getActiveBotConfigVersion());
            assertEquals(Identity.USER.getCode(), status.getActiveIdentityMode());
            assertEquals(ImBotTakeoverConfig.read(winner, new StandardEnvironment()).getStr("ownerUserId"), status.getActiveOwnerUserId());
            assertWecomConflict(b);
            service.enable(botOperation(winner), false);
            assertTrue(service.enable(botOperation(b), true).getEnabled());
        });
    }

    @Test void wecomSwitchingLegacyRootToBotRetainsThePersonalConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        ImBotUserEntity binding = authorize("legacy-owner");
        String privateUrl = "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture-legacy";
        String sharedUrl = "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture-shared";
        b.setPlatform(Platform.WECOM.getCode());
        b.setIdentityMode(Identity.USER.getCode());
        b.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj()
                .set("ownerUserId", userId).set("externalUserId", binding.getExternalUserId())
                .set("messageUserId", "legacy-message-owner").set("privateChatIds", Collections.singletonList("legacy-chat")), environment));
        b.setDocumentConfigCipher(ImBotMcpConfig.merge(null, null, Collections.singletonMap("msg", privateUrl), environment));
        b.setDocumentConfigCipher(ImBotMcpConfig.mergeApplication(b.getDocumentConfigCipher(), true, null,
                Collections.singletonMap("msg", sharedUrl), environment));
        bots.updateById(b);
        botIds.add(binding.getId());
        ImBotTakeoverServiceImpl takeover = new ImBotTakeoverServiceImpl(service, bots, environment);
        asBotAdmin(() -> {
            ImBotTakeoverRequestDTO request = new ImBotTakeoverRequestDTO();
            request.setBotKey(b.getId());
            request.setExpectedConfigVersion(1L);
            request.setIdentityMode(Identity.BOT.getCode());
            assertEquals(ImBotEnums.Error.WECOM_IDENTITY_CONFLICT.getCode(),
                    assertThrows(BizException.class, () -> tx.execute(status -> takeover.configure(request))).getCode());
            service.enable(botOperation(b), false);
            request.setExpectedConfigVersion(2L);
            assertEquals(Identity.BOT.getCode(), tx.execute(status -> takeover.configure(request)).getIdentityMode());
            assertEquals(sharedUrl, service.detail(b.getId(), true).getMcpUrls().get("msg"));
            ImBotTakeoverDTO retained = takeover.findProfile(b.getId(), userId);
            assertEquals(binding.getId(), retained.getBotKey());
            assertEquals(Collections.singletonList("legacy-chat"), retained.getPrivateChatIds());
            assertTrue(retained.getAuthorizationReady());
            assertEquals(privateUrl, service.detail(retained.getBotKey(), true).getMcpUrls().get("msg"));
            service.enable(botOperation(b), true);
            assertWecomConflict(bots.selectById(retained.getBotKey()));
            service.enable(botOperation(b), false);
            assertTrue(service.enable(botOperation(bots.selectById(retained.getBotKey())), true).getEnabled());
        });
    }

    private ImBotOperationDTO botOperation(ImBotEntity bot) {
        ImBotOperationDTO operation = new ImBotOperationDTO();
        operation.setBotKey(bot.getId());
        operation.setExpectedConfigVersion(bots.selectById(bot.getId()).getConfigVersion());
        return operation;
    }

    private void assertWecomConflict(ImBotEntity bot) {
        assertEquals(ImBotEnums.Error.WECOM_IDENTITY_CONFLICT.getCode(),
                assertThrows(BizException.class, () -> service.enable(botOperation(bot), true)).getCode());
    }

    private void asBotAdmin(Runnable action) {
        asUser(() -> {
            AdminTenantContext.establish("ZH000");
            AdminTenantContext.authorize();
            try {
                action.run();
            } finally {
                AdminTenantContext.clear();
            }
        });
    }

    private ImBotEntity wecomTakeoverProfile(String owner, String suffix) {
        StandardEnvironment environment = new StandardEnvironment();
        ImBotEntity profile = createBot(marker + "-" + suffix);
        String externalId = "fixture-" + suffix;
        ImBotUserEntity binding = new ImBotUserEntity();
        binding.setId(profile.getId());
        binding.setBotId(b.getId());
        binding.setTenantId(b.getTenantId());
        binding.setUserId(owner);
        binding.setAuthorized(ImBotServiceImpl.flag(true));
        binding.setExternalUserId(externalId);
        binding.setExternalUserKey(ImBotServiceImpl.hash(externalId));
        users.insert(binding);
        profile.setParentBotId(b.getId());
        profile.setPlatform(Platform.WECOM.getCode());
        profile.setIdentityMode(Identity.USER.getCode());
        profile.setEnabled(ImBotServiceImpl.flag(false));
        profile.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj().set("ownerUserId", owner)
                .set("externalUserId", externalId).set("messageUserId", externalId), environment));
        profile.setDocumentConfigCipher(ImBotMcpConfig.merge(null, null, Collections.singletonMap(
                "msg", "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture-" + suffix), environment));
        bots.updateById(profile);
        return profile;
    }

    @Test void sameApplicationSharesGroupAndRoutesOnlyToMentionedOwners() {
        ImBotUserEntity first=authorize("first");
        List<ImBotUserEntity> members=new ArrayList<>();
        members.add(first);
        for(String suffix:Arrays.asList("second","third")){
            ImBotUserEntity member=new ImBotUserEntity();
            member.setTenantId("ZH000");member.setBotId(b.getId());member.setUserId(userId+"-"+suffix);
            member.setUserName(suffix);member.setAuthorized(ImBotServiceImpl.flag(true));
            member.setExternalUserId(suffix);member.setExternalUserKey(ImBotServiceImpl.hash(suffix));
            users.insert(member);
            members.add(member);
        }
        ImBotTakeoverServiceImpl config=new ImBotTakeoverServiceImpl(service,bots,new StandardEnvironment());
        ImBotTakeover worker=takeover();
        String group="shared-group-"+marker;
        long now=System.currentTimeMillis();
        JSONArray history=new JSONArray()
                .put(personMessage("background","other",now-3000).set("text","群聊背景"))
                .put(personMessage("named-second","other",now-2000).set("text","@second 请回答"))
                .put(personMessage("named-two","other",now-1000).set("text","请两位回答")
                        .set("mentionedUserIds",Arrays.asList("first","third")));
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            for(ImBotUserEntity u:members){
                assertNull(config.findProfile(b.getId(),u.getUserId()));
                ImBotTakeoverDTO p=tx.execute(s->config.profile(b.getId(),u.getUserId()));botIds.add(p.getBotKey());
                assertEquals(u.getId(),p.getBotKey());assertFalse(p.getEnabled());
                assertEquals(p.getBotKey(),config.findProfile(b.getId(),u.getUserId()).getBotKey());
                ImBotTakeoverRequestDTO request=new ImBotTakeoverRequestDTO();request.setBotKey(p.getBotKey());
                request.setExpectedConfigVersion(p.getConfigVersion());request.setIdentityMode(Identity.USER.getCode());
                request.setOwnerUserId(u.getUserId());request.setExternalUserId(u.getExternalUserId());
                request.setGroupChatIds(Collections.singletonList(group));request.setGroupMentionName(u.getExternalUserId());
                if(u!=first){
                    request.setGroupMentionName(first.getExternalUserId());
                    assertThrows(BizException.class,()->tx.execute(s->config.configure(request)));
                    request.setGroupMentionName(u.getExternalUserId());
                }
                ImBotTakeoverDTO configured=tx.execute(s->config.configure(request));
                assertEquals(Collections.singletonList(group),configured.getGroupChatIds());
                assertEquals(u.getExternalUserId(),configured.getGroupMentionName());
            }
            for(ImBotUserEntity u:members){
                ImBotEntity profile=bots.selectById(u.getId());
                JSONObject settings=ImBotTakeoverConfig.read(profile,new StandardEnvironment())
                        .set("generation",UUID.randomUUID().toString()).set("startedAt",now-60000);
                profile.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(settings,new StandardEnvironment()));
                profile.setOwnerNode("node-a");profile.setOwnerVersion(1L);profile.setLeaseUntil(bots.databaseNow().plusHours(1));
                profile.setEnabled(ImBotServiceImpl.flag(true));profile.setAutoProcessEnabled(ImBotServiceImpl.flag(true));
                profile.setGroupEnabled(ImBotServiceImpl.flag(true));bots.updateById(profile);
                ImBotSessionEntity target=ReflectionTestUtils.invokeMethod(worker,"target",profile,settings,group,
                        SessionType.GROUP.getCode(),"共享测试群","node-a",profile.getOwnerVersion());
                ReflectionTestUtils.invokeMethod(worker,"ingest",profile,settings,target,history,"node-a",profile.getOwnerVersion());
                ReflectionTestUtils.invokeMethod(worker,"ingest",profile,settings,target,history,"node-a",profile.getOwnerVersion());
                List<ImBotMessageEntity> recorded=messages.selectList(new LambdaQueryWrapper<ImBotMessageEntity>()
                        .eq(ImBotMessageEntity::getBotId,profile.getId()));
                assertEquals(1,recorded.size());
                ImBotMessageEntity message=recorded.get(0);
                assertEquals("second".equals(u.getExternalUserId())?"named-second":"named-two",message.getExternalMessageId());
                assertEquals(u.getUserId(),message.getUserId());
                JSONObject context=ImBotTakeoverConfig.context(message);
                assertTrue(context.getBool("groupMentioned"));
                assertEquals("群聊背景",context.getJSONArray("history").getJSONObject(0).getStr("text"));
                assertEquals(Boolean.TRUE,ReflectionTestUtils.invokeMethod(worker,"groupReplyAllowed",profile,target,context));
                assertEquals(Boolean.FALSE,ReflectionTestUtils.invokeMethod(worker,"groupReplyAllowed",profile,target,new JSONObject()));
            }
            assertNull(service.user(members.get(1).getId(),first.getUserId()));
            assertEquals(1L,bots.selectById(b.getId()).getConfigVersion().longValue());
        });
    }

    @Test void configurationEncryptsSecretsAndChecksVersionUsingRealUpdates() {
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotSaveDTO request=new ImBotSaveDTO();request.setPlatform(Platform.FEISHU.getCode());request.setExternalAppId(marker+"-config");request.setSecret("test-only-secret");request.setName("配置验证");
            request.setAutoProcessEnabled(true);
            ImBotDTO created=service.save(request,true);botIds.add(created.getBotKey());assertFalse(created.getEnabled());
            assertTrue(created.getAutoProcessEnabled(),"通用对话自动处理不需要配置专家");
            String cipher=bots.selectById(created.getBotKey()).getSecretCipher();assertNotEquals("test-only-secret",cipher);assertTrue(created.getHasSecret());
            request.setBotKey(created.getBotKey());request.setExpectedConfigVersion(created.getConfigVersion());request.setSecret(null);request.setName("配置更新");
            ImBotDTO updated=service.save(request,false);assertEquals(2L,updated.getConfigVersion().longValue());assertEquals(cipher,bots.selectById(created.getBotKey()).getSecretCipher());
            assertThrows(Exception.class,()->service.save(request,false));
            ImBotOperationDTO operation=new ImBotOperationDTO();operation.setBotKey(created.getBotKey());operation.setExpectedConfigVersion(updated.getConfigVersion());assertTrue(service.enable(operation,true).getEnabled());
            assertThrows(Exception.class,()->service.enable(operation,false));
            AdminTenantContext.clear();
        });
    }

    @Test void inboundExecutorLogsRealDatabaseRejectionAndRetainsSdkFailureCause() throws Exception {
        own();
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(8);executor.initialize();
        StandardEnvironment environment=new StandardEnvironment();
        ImBotRuntime runtime=new ImBotRuntime(bots,messages,sessions,service,null,inbox,null,null,null,executor,executor,executor,tx,environment,new ChatScheduleNodeIdentity(environment));
        Class<?> ownerType=Class.forName(ImBotRuntime.class.getName()+"$Owner");
        java.lang.reflect.Constructor<?> constructor=ownerType.getDeclaredConstructor(ImBotRuntime.class,ImBotEntity.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(runtime,b);
        Logger logger=(Logger)LoggerFactory.getLogger(ImBotRuntime.class);ListAppender<ILoggingEvent> logs=new ListAppender<>();logs.start();logger.addAppender(logs);
        try {
            // 连接仍由 node-a 持有，运行器的真实节点与其不同，数据库会拒绝入站事务。
            b.setPlatform(Platform.WECOM.getCode());
            ReflectionTestUtils.invokeMethod(runtime,"receive",owner,event("runtime-event","runtime-user","private-runtime-message"));
            executor.submit(()->{}).get(10,TimeUnit.SECONDS);
            assertEquals(2,logs.list.size());assertTrue(logs.list.get(0).getFormattedMessage().contains("收到入站消息"));
            String diagnostic=logs.list.get(1).getFormattedMessage();
            assertTrue(diagnostic.contains("企微入站事务失败"));assertTrue(diagnostic.contains("botKey="+b.getId()));
            assertTrue(diagnostic.contains(Error.LEASE_LOST.getCode()));assertTrue(diagnostic.contains("ImBotInbox"));assertFalse(diagnostic.contains("private-runtime-message"));
            b.setPlatform(Platform.FEISHU.getCode());
            BizException failure=assertThrows(BizException.class,()->ReflectionTestUtils.invokeMethod(runtime,"receive",owner,event("runtime-event","runtime-user","private-runtime-message")));
            assertEquals(Error.INBOUND_FAILED.getCode(),failure.getCode());assertInstanceOf(ExecutionException.class,failure.getCause());
            assertEquals(Error.LEASE_LOST.getCode(),((BizException)failure.getCause().getCause()).getCode());
            assertEquals(5,logs.list.size());assertTrue(logs.list.get(3).getFormattedMessage().contains("飞书入站事务失败"));
            assertTrue(logs.list.get(4).getFormattedMessage().contains("飞书入库未确认"));
            for(ILoggingEvent entry:logs.list) {
                String detail=entry.getFormattedMessage();assertTrue(detail.contains("externalUserId=runtime-user"));
                assertTrue(detail.contains("externalAppId="+b.getExternalAppId()));assertTrue(detail.contains("externalMessageId=runtime-event"));
                assertFalse(detail.contains("private-runtime-message"));
            }
            assertEquals(0,messages.selectCount(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,b.getId())).intValue());
        } finally {logger.detachAppender(logs);logs.stop();runtime.close();executor.shutdown();}
    }

    @Test void callerRunsPolicyCannotMoveBlockingWorkOntoTheScanner() throws Exception {
        own();ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());executor.initialize();
        CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
        executor.execute(()->{occupied.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        assertTrue(occupied.await(5,TimeUnit.SECONDS));StandardEnvironment environment=new StandardEnvironment();
        ImBotRuntime runtime=new ImBotRuntime(bots,messages,sessions,service,null,inbox,null,null,null,executor,executor,executor,tx,environment,new ChatScheduleNodeIdentity(environment));
        Class<?> ownerType=Class.forName(ImBotRuntime.class.getName()+"$Owner");
        java.lang.reflect.Constructor<?> constructor=ownerType.getDeclaredConstructor(ImBotRuntime.class,ImBotEntity.class);constructor.setAccessible(true);
        Object owner=constructor.newInstance(runtime,b);java.util.concurrent.atomic.AtomicBoolean executed=new java.util.concurrent.atomic.AtomicBoolean(),rejected=new java.util.concurrent.atomic.AtomicBoolean();
        try {
            ReflectionTestUtils.invokeMethod(runtime,"submit",owner,null,(Runnable)()->executed.set(true),(Runnable)()->rejected.set(true));
            assertFalse(executed.get(),"共享线程池饱和时不能在扫描线程执行阻塞工作");assertTrue(rejected.get());
        } finally{release.countDown();runtime.close();executor.shutdown();}
    }
    @Test void duplicateConfigurationKeepsActualDatabaseCauseAndSafeDiagnostics() {
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotSaveDTO request=new ImBotSaveDTO();request.setPlatform(Platform.FEISHU.getCode());request.setExternalAppId(b.getExternalAppId());
            request.setSecret("test-only-duplicate-secret");request.setName("重复配置验证");
            BizException failure=assertThrows(BizException.class,()->service.save(request,true));
            assertEquals(BizException.CODE_PARAM_ERROR,failure.getCode());
            assertInstanceOf(org.springframework.dao.DuplicateKeyException.class,failure.getCause());
            String diagnostic=ExceptionLogUtil.safeStackTrace(failure);
            assertTrue(diagnostic.contains("sqlState=23000"));assertTrue(diagnostic.contains("vendorCode=1062"));
            assertTrue(diagnostic.contains("ImBotServiceImpl"));assertFalse(diagnostic.contains(request.getSecret()));
        });
    }

    @Test void documentHttpUsesRealTenantUserBindingAndRejectsUnlistedActions() {
        ImBotDocumentServiceImpl documents=new ImBotDocumentServiceImpl(service,new StandardEnvironment(),null,null,null,null,tx);
        asUser(()->assertThrows(BizException.class,()->documents.capabilities(b.getId())));
        ImBotUserEntity u=authorize(null);
        asUser(()->assertThrows(BizException.class,()->documents.capabilities(b.getId())));
        u.setExternalUserId("doc-user");u.setExternalUserKey(ImBotServiceImpl.hash("doc-user"));users.updateById(u);
        asUser(()->{
            assertTrue(documents.capabilities(b.getId()).getActions().contains("feishu.doc.read"));
            ImBotDocumentRequestDTO request=new ImBotDocumentRequestDTO();request.setBotKey(b.getId());request.setAction("http.any");
            assertThrows(BizException.class,()->documents.execute(request));
            request.setAction("wecom.doc.create");assertThrows(BizException.class,()->documents.execute(request));
            request.setAction("feishu.doc.read");request.setArguments(Collections.singletonMap("document_id","../auth"));
            assertThrows(BizException.class,()->documents.execute(request));
        });
        bots.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).set(ImBotEntity::getEnabled,ImBotServiceImpl.flag(false)));
        asUser(()->assertThrows(BizException.class,()->documents.capabilities(b.getId())));
    }
    @Test void documentConfigurationEncryptedVersionedPreservedClearedAndCommentedInRealDatabase()throws Exception {
        bots.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).set(ImBotEntity::getPlatform,Platform.WECOM.getCode()));
        String url="https://qyapi.weixin.qq.com/mcp?key=test-only-document-credential";
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotSaveDTO request=new ImBotSaveDTO();request.setBotKey(b.getId());request.setName(marker);request.setExpectedConfigVersion(1L);request.setDocumentMcpUrl(url);
            ImBotDTO saved=service.save(request,false);
            String cipher=bots.selectById(b.getId()).getDocumentConfigCipher();assertNotNull(cipher);assertFalse(cipher.contains("test-only"));
            assertEquals(url,ImBotMcpConfig.read(cipher,new StandardEnvironment()).get(cn.com.servyou.work.api.enums.ImBotMcpCapability.DOCUMENT.getCode()));
            assertFalse(JSONUtil.toJsonStr(saved).contains("documentMcpUrl"));assertFalse(JSONUtil.toJsonStr(saved).contains(cipher));
            assertThrows(BizException.class,()->service.save(request,false));
            request.setExpectedConfigVersion(2L);request.setDocumentMcpUrl(null);service.save(request,false);
            assertEquals(cipher,bots.selectById(b.getId()).getDocumentConfigCipher());
            request.setExpectedConfigVersion(3L);request.setDocumentMcpUrl("");service.save(request,false);
            assertNull(bots.selectById(b.getId()).getDocumentConfigCipher());
        });
        try(java.sql.Connection c=data.getConnection();PreparedStatement st=c.prepareStatement("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sw_im_bot' AND COLUMN_NAME='document_config_cipher'")) {
            try(ResultSet rs=st.executeQuery()){assertTrue(rs.next());assertTrue(rs.getString(1).contains("企微"));}
        }
    }


    @Test void personalMcpUrlsAreReturnedOnlyToBoundOwner() {
        ImBotUserEntity binding = authorize("wecom-owner");
        String url = "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=test-only-" + marker;
        b.setPlatform(Platform.WECOM.getCode());
        b.setIdentityMode(Identity.USER.getCode());
        b.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(JSONUtil.createObj()
                .set("ownerUserId", userId).set("externalUserId", binding.getExternalUserId()), new StandardEnvironment()));
        b.setDocumentConfigCipher(ImBotMcpConfig.merge(null, null,
                Collections.singletonMap("msg", url), new StandardEnvironment()));
        bots.updateById(b);
        asUser(() -> {
            AdminTenantContext.establish("ZH000");
            AdminTenantContext.authorize();
            assertEquals(url, service.detail(b.getId(), true).getMcpUrls().get("msg"));
            String owner = userId;
            try {
                userId = owner + "-other";
                asUser(() -> assertNull(service.detail(b.getId(), true).getMcpUrls()));
            } finally {
                userId = owner;
            }
        });
        binding.setAuthorized(ImBotServiceImpl.flag(false));
        users.updateById(binding);
        asUser(() -> {
            AdminTenantContext.establish("ZH000");
            AdminTenantContext.authorize();
            assertNull(service.detail(b.getId(), true).getMcpUrls());
        });
    }

    @Test void sharedApplicationCanBeReadAndUpdatedByAnUnboundTenantMember() {
        StandardEnvironment environment = new StandardEnvironment();
        String privateUrl = "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture-private";
        String sharedUrl = "https://qyapi.weixin.qq.com/mcp/v2/bot/msg?apikey=fixture-shared";
        String newSecret = "fixture-replacement-secret";
        b.setPlatform(Platform.WECOM.getCode());
        b.setIdentityMode(Identity.USER.getCode());
        b.setSecretCipher(cn.com.servyou.work.core.utils.AesCryptoUtil.encrypt(
                cn.com.servyou.work.core.utils.AesCryptoUtil.resolveKey(environment), "fixture-original-secret"));
        b.setDocumentConfigCipher(ImBotMcpConfig.merge(null, null, Collections.singletonMap("msg", privateUrl), environment));
        bots.updateById(b);
        ImBotEntity child = createBot(marker + "-profile");
        child.setParentBotId(b.getId());
        child.setIdentityMode(Identity.USER.getCode());
        child.setPlatform(Platform.WECOM.getCode());
        child.setAutoProcessEnabled(ImBotServiceImpl.flag(false));
        bots.updateById(child);
        ImBotQueryDTO query = new ImBotQueryDTO();
        query.setBotKey(b.getId());
        ImBotSaveDTO request = new ImBotSaveDTO();
        request.setBotKey(b.getId());
        request.setName("共享配置-" + marker);
        request.setExpectedConfigVersion(1L);
        request.setSecret(newSecret);
        request.setMcpUrls(Collections.singletonMap("msg", sharedUrl));
        request.setAutoProcessEnabled(true);
        request.setGroupEnabled(true);
        asUser(() -> {
            assertFalse(AdminTenantContext.isAuthorized());
            assertEquals(1, service.pageApplications(query).getRecords().size());
            assertNull(service.pageApplications(query).getRecords().get(0).getSecret());
            assertTrue(service.pageBots(query, false).getRecords().isEmpty());
            assertTrue(service.applicationDetail(b.getId()).getMcpUrls().isEmpty());
            assertThrows(BizException.class, () -> service.applicationDetail(child.getId()));
            assertNull(service.updateApplication(request).getSecret());
            assertThrows(BizException.class, () -> service.updateApplication(request));
            assertEquals(privateUrl, ImBotMcpConfig.read(bots.selectById(b.getId()).getDocumentConfigCipher(), environment).get("msg"));
            ImBotEntity profile = service.bot(child.getId());
            assertEquals(2L, profile.getConfigVersion().longValue());
            assertTrue(ImBotServiceImpl.yes(profile.getGroupEnabled()));
            assertEquals(sharedUrl, service.toolMcpUrls(profile).get("msg"));
        });
        asUser(userId + "-peer", "ZH000", () -> {
            ImBotDTO readback = service.applicationDetail(b.getId());
            assertEquals(newSecret, readback.getSecret());
            assertEquals(sharedUrl, readback.getMcpUrls().get("msg"));
            assertEquals(request.getName(), readback.getName());
        });
        asUser(userId + "-other-tenant", "ZH901", () -> {
            assertTrue(service.pageApplications(query).getRecords().isEmpty());
            assertThrows(BizException.class, () -> service.applicationDetail(b.getId()));
            assertThrows(BizException.class, () -> service.updateApplication(request));
        });
    }

    @Test void sevenCapabilityConfigurationUsesRealVersionedStorageAndUserAuthorization() {
        bots.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).set(ImBotEntity::getPlatform,Platform.WECOM.getCode()));
        java.util.Map<String,String> urls=new java.util.LinkedHashMap<>();
        for(cn.com.servyou.work.api.enums.ImBotMcpCapability capability:cn.com.servyou.work.api.enums.ImBotMcpCapability.values())
            urls.put(capability.getCode(),"https://qyapi.weixin.qq.com/mcp/v2/bot/"+capability.getCode()+"?apikey=test-only-"+marker);
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotSaveDTO request=new ImBotSaveDTO();request.setBotKey(b.getId());request.setName(marker);request.setExpectedConfigVersion(1L);request.setMcpUrls(urls);
            ImBotDTO saved=service.save(request,false);assertEquals(7,saved.getConfiguredCapabilities().size());
            String cipher=bots.selectById(b.getId()).getDocumentConfigCipher();assertFalse(cipher.contains(marker));
            assertEquals(urls,ImBotMcpConfig.read(cipher,new StandardEnvironment()));
            assertFalse(JSONUtil.toJsonStr(saved).contains("apikey"));assertFalse(JSONUtil.toJsonStr(request).contains("apikey"));
            assertNull(service.detail(b.getId(), false).getMcpUrls());
            assertEquals(urls, service.detail(b.getId(), true).getMcpUrls());
            request.setExpectedConfigVersion(2L);request.setMcpUrls(java.util.Collections.singletonMap("todo",""));
            saved=service.save(request,false);assertEquals(6,saved.getConfiguredCapabilities().size());assertTrue(saved.getDocumentConfigured());
            assertFalse(ImBotMcpConfig.read(bots.selectById(b.getId()).getDocumentConfigCipher(),new StandardEnvironment()).containsKey("todo"));
            request.setExpectedConfigVersion(3L);request.setMcpUrls(java.util.Collections.singletonMap("todo",urls.get("doc")));
            assertThrows(BizException.class,()->service.save(request,false));
        });
        ImBotToolServiceImpl tools=new ImBotToolServiceImpl(service,new StandardEnvironment(),null,null,null,null,tx);
        ImBotToolRequestDTO request=new ImBotToolRequestDTO();request.setBotKey(b.getId());request.setCapability("todo");
        asUser(()->assertThrows(BizException.class,()->tools.page(request)));
        ImBotUserEntity user=authorize(null);user.setExternalUserId("tools-bound-user");user.setExternalUserKey(ImBotServiceImpl.hash("tools-bound-user"));users.updateById(user);
        asUser(()->{
            BizException missing=assertThrows(BizException.class,()->tools.page(request));
            assertEquals(Error.TOOL_NOT_CONFIGURED.getCode(),missing.getCode());
            request.setCapability("arbitrary-url");assertThrows(BizException.class,()->tools.execute(request));
        });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WECOM_VERIFICATION_ENDPOINTS",matches=".+")
    void realDatabaseAuthorizedHttpServiceDiscoversAndCallsSevenLiveCapabilities() {
        bots.update(null,new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).set(ImBotEntity::getPlatform,Platform.WECOM.getCode()));
        java.util.Map<String,String> urls=new java.util.LinkedHashMap<>();
        JSONUtil.parseObj(System.getenv("WECOM_VERIFICATION_ENDPOINTS")).forEach((key,value)->urls.put(key,(String)value));
        asUser(()->{
            AdminTenantContext.establish("ZH000");AdminTenantContext.authorize();
            ImBotSaveDTO request=new ImBotSaveDTO();request.setBotKey(b.getId());request.setName(marker);request.setExpectedConfigVersion(1L);request.setMcpUrls(urls);service.save(request,false);
        });
        ImBotUserEntity user=authorize(null);user.setExternalUserId("tools-live-user");user.setExternalUserKey(ImBotServiceImpl.hash("tools-live-user"));users.updateById(user);
        ImBotToolServiceImpl tools=new ImBotToolServiceImpl(service,new StandardEnvironment(),new ImBotWecomDocuments(new okhttp3.OkHttpClient(),new cn.com.servyou.work.core.mcp.client.McpProtocolCodec(new com.fasterxml.jackson.databind.ObjectMapper())),null,null,null,tx);
        java.util.Map<String,String> nativeTools=new java.util.LinkedHashMap<>();
        nativeTools.put("doc","doc_search");nativeTools.put("msg","message_aibot_sessions_list");nativeTools.put("todo","todo_list");nativeTools.put("schedule","calendar_schedules_list");nativeTools.put("meeting","meeting_list");nativeTools.put("disk","disk_files_list");nativeTools.put("contact","contact_users_search");
        for(java.util.Map.Entry<String,String> entry:nativeTools.entrySet())asUser(()->{
            ImBotToolRequestDTO request=new ImBotToolRequestDTO();request.setBotKey(b.getId());request.setCapability(entry.getKey());request.setTool(entry.getValue());request.setPageSize(1);
            assertEquals(1,tools.page(request).getRecords().size());
            java.util.Map<String,Object> args=new java.util.LinkedHashMap<>();
            if(entry.getKey().equals("doc"))args.put("keywords",java.util.Collections.singletonList("AI工作台企微HTTP接口验证"));
            if(entry.getKey().equals("contact"))args.put("keywords",java.util.Collections.singletonList("测试员工甲"));
            if(entry.getKey().equals("todo") || entry.getKey().equals("disk") || entry.getKey().equals("meeting"))args.put("limit",1);
            if(entry.getKey().equals("schedule") || entry.getKey().equals("meeting")) {
                String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();args.put("begin_time",today+" 00:00:00");args.put("end_time",today+" 23:59:59");
            }
            request.setArguments(args);java.util.Map<String,Object> result=tools.execute(request);
            assertEquals(0,ImBotWecomToolLiveIntegrationTest.payload(result).getInt("errcode"));
            assertFalse(JSONUtil.toJsonStr(result).contains("extra_identity_context"));
        });
    }
}
