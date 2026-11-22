package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.enums.ImBotEnums;
import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.interfaces.imbot.IImBotTakeoverService;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.core.utils.*;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.ImBotMapper;
import cn.hutool.json.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 真人身份配置和正式用户OAuth，不接受调试台短期令牌，不改变现有用户绑定。 */
@Service
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotTakeoverServiceImpl implements IImBotTakeoverService {
    private final ImBotServiceImpl service;
    private final ImBotMapper bots;
    private final Environment environment;

    @Override public ImBotTakeoverDTO findProfile(Long key,String owner) {
        ImBotEntity root=service.accessibleBot(key,true,false);
        ImBotServiceImpl.text(owner,"ownerUserId",64);
        if(root.getParentBotId()!=null) throw BizException.paramError("请选择原机器人");
        if(ImBotTakeoverConfig.user(root) && owner.equals(ImBotTakeoverConfig.read(root,environment).getStr("ownerUserId"))) return view(root,true);
        ImBotUserEntity binding=service.user(key,owner);
        ImBotEntity child=binding==null?null:bots.selectById(binding.getId());
        return child!=null && key.equals(child.getParentBotId())?view(child,true):null;
    }

    /** 复用现有配置记录作为每个人的运行单元，原绑定记录是唯一的人员索引。 */
    @Override
    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ImBotTakeoverDTO profile(Long key, String owner) {
        ImBotEntity root = service.accessibleBot(key, true, true);
        if (root.getParentBotId() != null) throw BizException.paramError("请选择原机器人");
        root = service.lockBot(key);
        ImBotServiceImpl.text(owner,"ownerUserId",64);
        ImBotUserEntity binding = service.user(key,owner);
        if (binding == null || !ImBotServiceImpl.yes(binding.getAuthorized()) || StringUtils.isBlank(binding.getExternalUserId()))
            throw BizException.noPermission("请先在原机器人完成本人绑定");
        if (ImBotTakeoverConfig.user(root) && owner.equals(ImBotTakeoverConfig.read(root,environment).getStr("ownerUserId")))
            return view(root,true);
        ImBotEntity profile = bots.selectById(binding.getId());
        if (profile != null) {
            if (!key.equals(profile.getParentBotId())) throw BizException.paramError("接管配置标识冲突");
            return view(profile,true);
        }
        profile = newProfile(root, binding);
        JSONObject config = JSONUtil.createObj().set("ownerUserId",owner).set("externalUserId",binding.getExternalUserId());
        profile.setTakeoverConfigCipher(ImBotTakeoverConfig.encrypt(config,environment));
        bots.insert(profile);
        return view(profile,true);
    }

    private ImBotEntity newProfile(ImBotEntity root, ImBotUserEntity binding) {
        ImBotEntity profile = new ImBotEntity();
        profile.setId(binding.getId());
        profile.setParentBotId(root.getId());
        profile.setAppIdKey(ImBotServiceImpl.hash("profile:"+root.getId()+":"+binding.getId()));
        profile.setExternalAppId(root.getExternalAppId());
        profile.setPlatform(root.getPlatform());
        profile.setTenantId(root.getTenantId());
        profile.setName(StringUtils.left(root.getName()+" / "+binding.getUserId(),128));
        profile.setSecretCipher(""); // 应用密钥始终从原机器人读取。
        profile.setEnabled(ImBotServiceImpl.flag(false));
        profile.setAutoProcessEnabled(root.getAutoProcessEnabled());
        profile.setGroupEnabled(root.getGroupEnabled());
        profile.setConnectionState(Connection.DISABLED.getCode());
        profile.setIdentityMode(Identity.USER.getCode());
        profile.setConfigVersion(1L);
        profile.setOwnerVersion(0L);
        profile.setCreateUserId(UserInfoUtil.getUserId());
        profile.setUpdateUserId(UserInfoUtil.getUserId());
        return profile;
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @Override public ImBotTakeoverDTO configure(ImBotTakeoverRequestDTO r){
        ImBotEntity b=service.accessibleBot(r.getBotKey(),true,true);
        Identity mode=ImBotEnums.require(Identity.class,r.getIdentityMode());
        Long rootId = b.getParentBotId()==null ? b.getId() : b.getParentBotId();
        ImBotEntity root = service.lockBot(rootId);
        if (b.getParentBotId() == null) b = root;
        if (mode == Identity.BOT && b.getParentBotId() == null && ImBotTakeoverConfig.user(b)
                && Platform.WECOM.getCode().equals(b.getPlatform())) {
            if (!Objects.equals(r.getExpectedConfigVersion(), b.getConfigVersion()))
                throw new BizException(Error.VERSION_CONFLICT.getCode(), "配置已变化，请刷新后重试");
            if (ImBotServiceImpl.yes(b.getEnabled()))
                throw new BizException(Error.WECOM_IDENTITY_CONFLICT.getCode(), "请先停用当前企微真人接管，再切换为普通机器人");
            retainLegacyWecomProfile(b);
        }
        if (b.getParentBotId()!=null && (mode!=Identity.USER || !Objects.equals(r.getOwnerUserId(), ImBotTakeoverConfig.read(b,environment).getStr("ownerUserId"))))
            throw BizException.paramError("人员配置仅允许修改本人的接管范围");
        JSONObject c=new JSONObject();
        if(mode==Identity.USER){
            ImBotServiceImpl.text(r.getOwnerUserId(),"ownerUserId",64);ImBotServiceImpl.text(r.getExternalUserId(),"externalUserId",256);
            ImBotUserEntity u=service.user(b.getId(),r.getOwnerUserId());
            if(u==null || !ImBotServiceImpl.yes(u.getAuthorized()) || !r.getExternalUserId().equals(u.getExternalUserId())){
                log.warn("真人接管绑定校验失败 botKey={}, platform={}, ownerUserId={}, externalUserId={}, boundExternalUserId={}, messageUserId={}",
                        b.getId(),b.getPlatform(),r.getOwnerUserId(),r.getExternalUserId(),u==null?null:u.getExternalUserId(),r.getMessageUserId());
                throw BizException.noPermission("接管本人须已授权且与现有绑定一致；企微机器人绑定ID填externalUserId，消息MCP本人ID填messageUserId");
            }
            c=ImBotTakeoverConfig.read(b,environment);
            if(!r.getOwnerUserId().equals(c.getStr("ownerUserId")) || !r.getExternalUserId().equals(c.getStr("externalUserId")))c=new JSONObject();
            boolean dingTalk=Platform.DINGTALK.getCode().equals(b.getPlatform());
            String messageUserId=dingTalk?c.getStr("messageUserId"):Platform.WECOM.getCode().equals(b.getPlatform())
                    ?ImBotServiceImpl.text(r.getMessageUserId(),"企微消息MCP本人ID(messageUserId)",256):r.getExternalUserId();
            List<String> groups=ids(r.getGroupChatIds());
            String mentionName=r.getGroupMentionName()==null?c.getStr("groupMentionName"):StringUtils.trimToNull(r.getGroupMentionName());
            if (mentionName!=null) {
                ImBotServiceImpl.text(mentionName,"群聊点名名称",64);
                if (mentionName.contains("@") || ImBotGroupMentions.broadcastName(mentionName))
                    throw BizException.paramError("群聊点名名称请填写本人在群内的名称");
            }
            for (ImBotEntity other : service.family(b)) {
                if (other.getId().equals(b.getId()) || !ImBotTakeoverConfig.user(other)) continue;
                JSONObject config = ImBotTakeoverConfig.read(other,environment);
                if (r.getOwnerUserId().equals(config.getStr("ownerUserId"))
                        || (StringUtils.isNotBlank(messageUserId) && messageUserId.equals(config.getStr("messageUserId"))))
                    throw BizException.paramError("该人员已有接管配置，请使用本人的配置");
                if (mentionName!=null && mentionName.equals(config.getStr("groupMentionName"))
                        && !Collections.disjoint(groups, ImBotTakeoverConfig.list(config,"groupChatIds")))
                    throw BizException.paramError("同一群内的接管人须使用不同的点名名称");
            }
            c.set("ownerUserId",r.getOwnerUserId()).set("externalUserId",r.getExternalUserId()).set("messageUserId",messageUserId)
                .set("privateChatIds",ids(r.getPrivateChatIds())).set("groupChatIds",groups).set("groupMentionName",mentionName)
                .set("discoverEnabled",Boolean.TRUE.equals(r.getDiscoverEnabled()));
            c.remove("discovery");c.remove("targetsSeeded");c.remove("sessionAfter");c.remove("oauthState");c.remove("oauthExpiresAt");
            if (dingTalk) clearDingTalkGrant(c);
        }
        update(b,r.getExpectedConfigVersion(),mode.getCode(),c);
        log.info("真人接管配置已修改并停用 botKey={}, platform={}, ownerUserId={}, externalUserId={}, messageUserId={}, operator={}",b.getId(),b.getPlatform(),r.getOwnerUserId(),r.getExternalUserId(),c.getStr("messageUserId"),UserInfoUtil.getUserId());
        return view(b,true);
    }

    /** 原机器人切换为普通机器人时，将旧版真人设置保留在该人员已有的配置模型中。 */
    private void retainLegacyWecomProfile(ImBotEntity bot) {
        JSONObject config = ImBotTakeoverConfig.read(bot, environment);
        String owner = config.getStr("ownerUserId");
        if (StringUtils.isBlank(owner)) return;
        ImBotUserEntity binding = service.user(bot.getId(), owner);
        if (binding == null) return;
        ImBotEntity existing = bots.selectById(binding.getId());
        if (existing != null) {
            if (!bot.getId().equals(existing.getParentBotId())) throw BizException.paramError("接管配置标识冲突");
            return;
        }
        ImBotEntity profile = newProfile(bot, binding);
        profile.setTakeoverConfigCipher(bot.getTakeoverConfigCipher());
        profile.setDocumentConfigCipher(ImBotMcpConfig.copyPersonal(bot.getDocumentConfigCipher(), environment));
        bots.insert(profile);
    }
    private List<String> ids(List<String> values){
        if(values==null)return Collections.emptyList();if(values.size()>100)throw BizException.paramError("显式会话最多100个");
        Set<String> unique=new LinkedHashSet<>();for(String s:values)unique.add(ImBotServiceImpl.text(s,"chatId",256));return new ArrayList<>(unique);
    }
    private ImBotEntity own(Long key){
        ImBotEntity b=service.accessibleBot(key,false,false);JSONObject c=ImBotTakeoverConfig.read(b,environment);
        ImBotUserEntity u=service.user(key,UserInfoUtil.getUserId());
        if(!ImBotTakeoverConfig.user(b) || !UserInfoUtil.getUserId().equals(c.getStr("ownerUserId")) || u==null
                || !ImBotServiceImpl.yes(u.getAuthorized()) || !Objects.equals(u.getExternalUserId(),c.getStr("externalUserId")))
            throw BizException.noPermission("仅已绑定的接管本人可授权或查看状态");
        return b;
    }
    @Override public ImBotTakeoverDTO status(Long key,boolean admin){return view(admin?service.accessibleBot(key,true,false):own(key),admin);}
    private ImBotTakeoverDTO view(ImBotEntity b,boolean admin){
        JSONObject c=ImBotTakeoverConfig.read(b,environment);ImBotTakeoverDTO v=new ImBotTakeoverDTO();
        v.setBotKey(b.getId());v.setConfigVersion(b.getConfigVersion());v.setIdentityMode(StringUtils.defaultIfBlank(b.getIdentityMode(),Identity.BOT.getCode()));
        v.setOwnerUserId(c.getStr("ownerUserId"));v.setExternalUserId(c.getStr("externalUserId"));
        v.setMessageUserId(StringUtils.defaultString(Platform.FEISHU.getCode().equals(b.getPlatform())?c.getStr("externalUserId"):c.getStr("messageUserId")));
        v.setPrivateChatIds(ImBotTakeoverConfig.list(c,"privateChatIds"));v.setGroupChatIds(ImBotTakeoverConfig.list(c,"groupChatIds"));v.setDiscoverEnabled(c.getBool("discoverEnabled",false));
        v.setGroupMentionName(c.getStr("groupMentionName"));
        v.setEnabled(ImBotServiceImpl.yes(b.getEnabled()));
        if (Platform.DINGTALK.getCode().equals(b.getPlatform())) {
            boolean ready=DingTalkUserAuthorization.ready(c);
            v.setAuthorizationReady(ready);
            v.setStatusMessage(StringUtils.defaultIfBlank(b.getLastError(),ready
                    ?"钉钉本人授权已就绪，支持私聊和群聊文本回复":"请完成钉钉本人设备授权"));
            return v;
        }
        v.setAuthorizationReady(Platform.FEISHU.getCode().equals(b.getPlatform())?StringUtils.isNotBlank(c.getStr("refreshToken")) && c.getLong("refreshExpiresAt",0L)>System.currentTimeMillis()
                :ImBotMcpConfig.read(b.getDocumentConfigCipher(),environment).containsKey(ImBotMcpCapability.MESSAGE.getCode()));
        v.setStatusMessage(StringUtils.defaultIfBlank(b.getLastError(),Platform.WECOM.getCode().equals(b.getPlatform())?
                "企微可发现活跃群；新私聊需配置对方userid。本人后续消息覆盖同会话较早输入。":"飞书搜索发现需用户令牌授予search:message；存在搜索索引延迟。本人后续消息覆盖同会话较早输入。"));return v;
    }
    @Override public ImBotTakeoverDTO startAuthorization(ImBotTakeoverRequestDTO r){
        ImBotEntity b=own(r.getBotKey());
        if (Platform.DINGTALK.getCode().equals(b.getPlatform())) {
            return startDingTalkAuthorization(b,r.getExpectedConfigVersion());
        }
        requireFeishu(b);
        HttpUrl redirect=HttpUrl.parse(StringUtils.defaultString(r.getRedirectUri()));
        if(redirect==null || !redirect.isHttps() || !redirect.username().isEmpty() || !redirect.password().isEmpty() || redirect.fragment()!=null)
            throw BizException.paramError("redirectUri须为飞书已登记的HTTPS回调地址，不带fragment或认证信息");
        JSONObject c=ImBotTakeoverConfig.read(b,environment);String state=UUID.randomUUID().toString()+UUID.randomUUID().toString();
        c.set("oauthState",ImBotServiceImpl.hash(state)).set("oauthExpiresAt",System.currentTimeMillis()+600000).set("redirectUri",redirect.toString());
        update(b,r.getExpectedConfigVersion(),Identity.USER.getCode(),c);
        String scopes=feishuAuthorizationScopes(c.getBool("discoverEnabled",false));
        ImBotTakeoverDTO result=view(b,false);result.setAuthorizationUrl(HttpUrl.get("https://accounts.feishu.cn/open-apis/authen/v1/authorize").newBuilder()
                .addQueryParameter("client_id",b.getExternalAppId()).addQueryParameter("response_type","code").addQueryParameter("redirect_uri",redirect.toString())
                .addQueryParameter("scope",scopes).addQueryParameter("state",state).build().toString());return result;
    }
    /** 按官方权限组合申请真人收发，搜索权限仅在开启会话发现时申请。 */
    static String feishuAuthorizationScopes(boolean discoverEnabled){
        List<String> scopes=new ArrayList<>(Arrays.asList(FeishuScope.OFFLINE_ACCESS.getCode(),FeishuScope.MESSAGE.getCode(),
                FeishuScope.MESSAGE_READONLY.getCode(),FeishuScope.PRIVATE_MESSAGES.getCode(),
                FeishuScope.GROUP_MESSAGES.getCode(),FeishuScope.SEND_AS_USER.getCode()));
        if(discoverEnabled)scopes.add(FeishuScope.SEARCH_MESSAGES.getCode());
        return StringUtils.join(scopes,' ');
    }
    @Override public ImBotTakeoverDTO completeAuthorization(ImBotTakeoverRequestDTO r){
        ImBotEntity b=own(r.getBotKey());
        if (Platform.DINGTALK.getCode().equals(b.getPlatform())) {
            return completeDingTalkAuthorization(b,r.getExpectedConfigVersion());
        }
        requireFeishu(b);JSONObject c=ImBotTakeoverConfig.read(b,environment);
        if(StringUtils.isBlank(r.getState()) || r.getState().length()>256 || !Objects.equals(c.getStr("oauthState"),ImBotServiceImpl.hash(r.getState()))
                || c.getLong("oauthExpiresAt",0L)<=System.currentTimeMillis())throw BizException.paramError("授权state已失效，请重新开始授权");
        ImBotServiceImpl.text(r.getCode(),"code",4096);String redirect=c.getStr("redirectUri");
        c.remove("oauthState");c.remove("oauthExpiresAt");
        // 先按配置版本一次性消费state；平台交换不占用数据库事务。
        update(b,r.getExpectedConfigVersion(),Identity.USER.getCode(),c);
        try {
            JSONObject request=JSONUtil.createObj().set("grant_type","authorization_code").set("code",r.getCode()).set("redirect_uri",redirect);
            JSONObject token=exchange(b,request);
            JSONObject identity=ImBotUserGateway.feishu("GET","/authen/v1/user_info",token.getStr("access_token"),null).getJSONObject("data");
            if(identity==null || !Objects.equals(c.getStr("externalUserId"),identity.getStr("open_id")))throw BizException.noPermission("飞书授权人不是配置的接管本人，请使用正确账号授权");
            saveToken(c,token);update(b,b.getConfigVersion(),Identity.USER.getCode(),c);
            log.info("飞书真人授权完成 botKey={}, ownerUserId={}, externalUserId={}",b.getId(),c.getStr("ownerUserId"),c.getStr("externalUserId"));return view(b,false);
        }catch(Exception e){log.warn("飞书真人OAuth交换失败 botKey={}\n{}",b.getId(),safeStackTrace(e));if(e instanceof BizException)throw (BizException)e;
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书用户授权失败，请重新开始授权并检查后台日志");}
    }
    /** 从钉钉官方托管应用获取本人设备授权链接。 */
    private ImBotTakeoverDTO startDingTalkAuthorization(ImBotEntity bot,Long expectedVersion) {
        requireDingTalkVersion(bot,expectedVersion);
        JSONObject config=ImBotTakeoverConfig.read(bot,environment);
        try {
            config.remove("accessToken");
            config.remove("refreshToken");
            config.remove("accessExpiresAt");
            config.remove("messageUserId");
            config.remove("refreshing");
            String url=DingTalkUserAuthorization.start(config);
            update(bot,expectedVersion,Identity.USER.getCode(),config);
            ImBotTakeoverDTO result=view(bot,false);
            result.setAuthorizationUrl(url);
            return result;
        } catch(Exception e) {
            throw dingTalkAuthorizationFailure(bot,e);
        }
    }

    /** 本人确认后核对组织和员工标识，将令牌保存到现有加密配置。 */
    private ImBotTakeoverDTO completeDingTalkAuthorization(ImBotEntity bot,Long expectedVersion) {
        requireDingTalkVersion(bot,expectedVersion);
        JSONObject config=ImBotTakeoverConfig.read(bot,environment);
        try {
            String code=DingTalkUserAuthorization.authorizationCode(config);
            String clientId=config.getStr("dingTalkOAuthClientId");
            clearDingTalkGrant(config);
            update(bot,expectedVersion,Identity.USER.getCode(),config);
            JSONObject token=DingTalkUserAuthorization.exchange(clientId,code);
            if (StringUtils.isNotBlank(config.getStr("dingTalkCorpId")) && !config.getStr("dingTalkCorpId").equals(token.getStr("corpId"))) {
                throw BizException.noPermission("请选择上次绑定的钉钉组织完成授权");
            }
            config.set("messageUserId",DingTalkUserAuthorization.verifyOwner(token,config.getStr("externalUserId")));
            DingTalkUserAuthorization.saveToken(config,token);
            update(bot,bot.getConfigVersion(),Identity.USER.getCode(),config);
            log.info("钉钉真人授权校验通过 botKey={}, ownerUserId={}, externalUserId={}",
                    bot.getId(),config.getStr("ownerUserId"),config.getStr("externalUserId"));
            return view(bot,false);
        } catch(Exception e) {
            throw dingTalkAuthorizationFailure(bot,e);
        }
    }

    private void requireDingTalkVersion(ImBotEntity bot,Long expectedVersion) {
        if (expectedVersion==null || !expectedVersion.equals(bot.getConfigVersion())) {
            throw new BizException(Error.VERSION_CONFLICT.getCode(),"配置已变化，请刷新后重试");
        }
    }

    private void clearDingTalkGrant(JSONObject config) {
        config.remove("dingTalkDeviceCode");
        config.remove("dingTalkFlowId");
        config.remove("oauthExpiresAt");
    }

    private BizException dingTalkAuthorizationFailure(ImBotEntity bot,Exception failure) {
        log.warn("钉钉真人授权校验失败 botKey={}\n{}",bot.getId(),safeStackTrace(failure));
        return failure instanceof BizException ? (BizException) failure :
                new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"钉钉本人授权校验失败，请检查后台日志");
    }

    JSONObject exchange(ImBotEntity b,JSONObject request)throws Exception {
        request.set("client_id",b.getExternalAppId()).set("client_secret",AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),b.getSecretCipher()));
        OkHttpClientUtil.HttpResult result=OkHttpClientUtil.jsonNoRetry("POST","https://accounts.feishu.cn/oauth/v3/token",Collections.emptyMap(),request.toString());
        JSONObject token=JSONUtil.parseObj(result.getBody());
        if(!result.isSuccessful() || token.getInt("code",0)!=0 || StringUtils.isBlank(token.getStr("access_token"))){
            log.warn("飞书真人令牌交换失败 botKey={}, grantType={}, httpStatus={}, providerCode={}",b.getId(),request.getStr("grant_type"),result.getStatusCode(),token.getInt("code",-1));
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书用户令牌交换失败，平台code="+token.getInt("code",-1));
        }
        return token;
    }
    static void saveToken(JSONObject c,JSONObject token){
        Long seconds=token.getLong("expires_in"),refreshSeconds=token.getLong("refresh_token_expires_in");
        if(seconds==null || seconds<=60 || refreshSeconds==null || refreshSeconds<=60 || StringUtils.isBlank(token.getStr("refresh_token")))
            throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书未返回有效续期令牌，请确认本次用户授权包含offline_access");
        c.set("accessToken",token.getStr("access_token")).set("refreshToken",token.getStr("refresh_token")).set("scope",token.getStr("scope"))
            .set("accessExpiresAt",System.currentTimeMillis()+seconds*1000).set("refreshExpiresAt",System.currentTimeMillis()+refreshSeconds*1000);c.remove("refreshing");
    }
    private void requireFeishu(ImBotEntity b){if(!Platform.FEISHU.getCode().equals(b.getPlatform()))throw BizException.paramError("企微使用现有消息MCP授权，不走飞书OAuth");}
    private void update(ImBotEntity b,Long expected,String mode,JSONObject c){
        if(expected==null || expected==Long.MAX_VALUE)throw BizException.paramError("expectedConfigVersion不合法");
        String cipher=ImBotTakeoverConfig.encrypt(c,environment);
        boolean changingMode = b.getParentBotId() == null && Platform.WECOM.getCode().equals(b.getPlatform())
                && ImBotTakeoverConfig.user(b) != Identity.USER.getCode().equals(mode);
        String mcpCipher = changingMode ? ImBotMcpConfig.separateApplication(b.getDocumentConfigCipher(),
                ImBotTakeoverConfig.user(b), environment) : b.getDocumentConfigCipher();
        int n=bots.update(null,new LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).eq(ImBotEntity::getConfigVersion,expected)
                .set(ImBotEntity::getIdentityMode,mode).set(ImBotEntity::getTakeoverConfigCipher,cipher).set(ImBotEntity::getEnabled,ImBotServiceImpl.flag(false))
                .set(changingMode, ImBotEntity::getDocumentConfigCipher, mcpCipher)
                .set(ImBotEntity::getConnectionState,Connection.DISABLED.getCode()).set(ImBotEntity::getLastError,null)
                .set(ImBotEntity::getConfigVersion,expected+1).set(ImBotEntity::getUpdateUserId,UserInfoUtil.getUserId()));
        if(n!=1)throw new BizException(Error.VERSION_CONFLICT.getCode(),"配置已变化，请刷新后重试");
        b.setIdentityMode(mode);b.setTakeoverConfigCipher(cipher);b.setDocumentConfigCipher(mcpCipher);b.setConfigVersion(expected+1);b.setEnabled(ImBotServiceImpl.flag(false));b.setLastError(null);
    }
}
