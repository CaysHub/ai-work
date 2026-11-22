package cn.com.servyou.work.core.imbot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import cn.com.servyou.work.api.enums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.*;
import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.api.interfaces.imbot.IImBotService;
import cn.com.servyou.work.api.model.imbot.*;
import cn.com.servyou.work.core.manager.accountcenter.IAccountCenterManager;
import cn.com.servyou.work.core.manager.accountcenter.TenantMemberDirectory;
import cn.com.servyou.work.core.manager.admin.AdminOpLogRecorder;
import cn.com.servyou.work.core.utils.*;
import cn.com.servyou.work.dao.entity.*;
import cn.com.servyou.work.dao.mapper.*;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.com.servyou.work.api.enums.ImBotEnums.require;
import static cn.com.servyou.work.core.utils.ExceptionLogUtil.safeStackTrace;

/** 仅维护机器人配置、授权、外部会话和消息；执行和认证继续走原服务。 */
@Service
@ConditionalOnProperty(prefix = "servyou.work.im-bot", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class ImBotServiceImpl implements IImBotService {
    private final ImBotMapper bots;
    private final ImBotUserMapper users;
    private final ImBotSessionMapper sessions;
    private final ImBotMessageMapper messages;
    private final TenantMemberDirectory members;
    private final IAccountCenterManager accounts;
    private final AdminOpLogRecorder audit;
    private final TransactionTemplate tx;
    private final Environment environment;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.com.servyou.work.api.interfaces.conversation.IConversationService conversations;

    static boolean yes(Integer value) { return BinaryFlagEnum.YES.getCode().equals(value); }
    static int flag(Boolean value) { return Boolean.TRUE.equals(value) ? BinaryFlagEnum.YES.getCode() : BinaryFlagEnum.NO.getCode(); }
    static String hash(String value) { return DigestUtil.sha256Hex(value); }
    static String text(String value, String field, int max) {
        if (StringUtils.isBlank(value) || value.length() > max || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) throw BizException.paramError(field + "不合法");
        return value;
    }
    static String limited(String value, int max) { return StringUtils.left(value, max); }
    private static <T> Page<T> page(ImBotQueryDTO q) {
        int n = q.getPageNum() == null ? 1 : q.getPageNum(), s = q.getPageSize() == null ? 20 : q.getPageSize();
        if (n < 1 || s < 1 || s > 100) throw BizException.paramError("分页参数不合法，pageSize最大100");
        return new Page<>(n, s);
    }
    private String scope(boolean admin) {
        if (!admin) return UserInfoUtil.getTenantId();
        if (!AdminTenantContext.isAuthorized()) throw BizException.noPermission("需要管理员权限");
        return AdminTenantContext.queryTenant();
    }
    ImBotEntity bot(Long key) {
        if (key == null) throw BizException.paramError("botKey必填");
        ImBotEntity b = bots.selectById(key);
        if (b == null) throw BizException.paramError("机器人不存在");
        if (b.getParentBotId()!=null) b.setSecretCipher(bots.selectById(b.getParentBotId()).getSecretCipher());
        return b;
    }
    ImBotEntity accessibleBot(Long key, boolean admin, boolean write) {
        scope(admin);
        ImBotEntity b = bot(key);
        if (admin) {
            if (!AdminTenantContext.includes(b.getTenantId()) || write && !b.getTenantId().equals(AdminTenantContext.requireTarget()))
                throw BizException.noPermission("机器人不在当前管理租户");
        } else if (!b.getTenantId().equals(UserInfoUtil.getTenantId())) throw BizException.noPermission("无权访问机器人");
        return b;
    }
    /** 相同平台应用下的原机器人和所有人员配置。 */
    List<ImBotEntity> family(ImBotEntity b) {
        Long root = b.getParentBotId()==null ? b.getId() : b.getParentBotId();
        return bots.selectList(new LambdaQueryWrapper<ImBotEntity>().eq(ImBotEntity::getId,root).or().eq(ImBotEntity::getParentBotId,root));
    }
    ImBotUserEntity user(Long key, String userId) {
        return users.binding(key,userId);
    }
    /** 查询原机器人最近的待绑定截止时间，短时监听随绑定码到期关闭。 */
    LocalDateTime bindingDeadline(Long key) {
        ImBotUserEntity u=users.selectOne(new LambdaQueryWrapper<ImBotUserEntity>()
                .eq(ImBotUserEntity::getBotId,key).isNotNull(ImBotUserEntity::getBindingCodeHash)
                .gt(ImBotUserEntity::getBindingCodeExpiresAt,bots.databaseNow())
                .orderByDesc(ImBotUserEntity::getBindingCodeExpiresAt).last("LIMIT 1"));
        return u==null?null:u.getBindingCodeExpiresAt();
    }
    boolean authorized(ImBotEntity b, String userId) {
        ImBotUserEntity u=user(b.getId(),userId);
        return yes(b.getEnabled()) && u!=null && yes(u.getAuthorized()) && u.getExternalUserKey()!=null;
    }
    private ImBotUserEntity requireUser(ImBotEntity b, boolean bound) {
        ImBotUserEntity u=user(b.getId(), UserInfoUtil.getUserId());
        if(u==null || !yes(u.getAuthorized()) || bound && u.getExternalUserKey()==null) throw BizException.noPermission("机器人未授权或未绑定");
        return u;
    }
    /** 文档入口沿用当前租户、机器人启用及用户授权绑定，不要求持有监听节点。 */
    ImBotEntity requireDocumentBot(Long key) {
        ImBotEntity b=accessibleBot(key,false,false);ImBotUserEntity binding=requireUser(b,true);
        if(ImBotTakeoverConfig.user(b) && (!UserInfoUtil.getUserId().equals(ImBotTakeoverConfig.read(b,environment).getStr("ownerUserId")) || !Objects.equals(binding.getExternalUserId(),ImBotTakeoverConfig.read(b,environment).getStr("externalUserId"))))
            throw BizException.noPermission("仅接管本人可使用个人工具授权");
        if(!yes(b.getEnabled()))throw BizException.noPermission("机器人已停用");
        return b;
    }
    boolean sendable(ImBotEntity b, ImBotSessionEntity s) {
        if(ImBotTakeoverConfig.user(b)) {
            cn.hutool.json.JSONObject c=ImBotTakeoverConfig.read(b,environment);
            if (s!=null && SessionType.GROUP.getCode().equals(s.getSessionType())
                    && !ImBotTakeoverConfig.list(c,"groupChatIds").contains(s.getExternalChatId()) && family(b).size()>1) return false;
            ImBotUserEntity owner=user(b.getId(),c.getStr("ownerUserId"));
            return s!=null && b.getId().equals(s.getBotId()) && b.getTenantId().equals(s.getTenantId())
                    && Objects.equals(c.getStr("ownerUserId"),s.getUserId()) && owner!=null && yes(owner.getAuthorized())
                    && Objects.equals(owner.getExternalUserId(),c.getStr("externalUserId")) && yes(b.getEnabled()) && yes(b.getAutoProcessEnabled())
                    && s.getChatKey().equals(hash(Identity.USER.getCode()+":"+c.getStr("generation")+":"+s.getExternalChatId()))
                    && (!SessionType.GROUP.getCode().equals(s.getSessionType()) || yes(b.getGroupEnabled()));
        }
        return s!=null && b.getId().equals(s.getBotId()) && b.getTenantId().equals(s.getTenantId())
                && authorized(b,s.getUserId()) && (!SessionType.GROUP.getCode().equals(s.getSessionType())
                || yes(b.getGroupEnabled()) && groups(b).contains(s.getExternalChatId()));
    }
    void verifySender(ImBotMessageEntity message) {
        accounts.runAsPortalUser(message.getTenantId(),message.getUserId(),()->{});
    }
    static List<String> groups(ImBotEntity b) {
        return StringUtils.isBlank(b.getAllowedGroupChatIds()) ? Collections.emptyList() : JSONUtil.toList(b.getAllowedGroupChatIds(),String.class);
    }
    @Override public IPage<ImBotDTO> pageBots(ImBotQueryDTO q, boolean admin) {
        return pageBots(q, admin, false);
    }

    @Override public IPage<ImBotDTO> pageApplications(ImBotQueryDTO q) {
        return pageBots(q, false, true);
    }

    private IPage<ImBotDTO> pageBots(ImBotQueryDTO q, boolean admin, boolean application) {
        String tenant=scope(admin);
        if(q.getPlatform()!=null) require(Platform.class,q.getPlatform());
        if(q.getConnectionState()!=null) require(Connection.class,q.getConnectionState());
        if(q.getEnabled()!=null && !BinaryFlagEnum.contains(q.getEnabled())) throw BizException.paramError("enabled不合法");
        LambdaQueryWrapper<ImBotEntity> w=new LambdaQueryWrapper<ImBotEntity>().eq(tenant!=null,ImBotEntity::getTenantId,tenant)
                .eq(q.getBotKey()!=null,ImBotEntity::getId,q.getBotKey()).eq(q.getPlatform()!=null,ImBotEntity::getPlatform,q.getPlatform())
                .eq(q.getEnabled()!=null,ImBotEntity::getEnabled,q.getEnabled()).eq(q.getConnectionState()!=null,ImBotEntity::getConnectionState,q.getConnectionState())
                .like(StringUtils.isNotBlank(q.getKeyword()),ImBotEntity::getName,q.getKeyword());
        w.isNull(ImBotEntity::getParentBotId);
        if(!admin && !application) w.apply("id IN (SELECT bot_id FROM sw_im_bot_user WHERE tenant_id={0} AND user_id={1} AND authorized={2})",tenant,UserInfoUtil.getUserId(),flag(true));
        return bots.selectPage(ImBotServiceImpl.<ImBotEntity>page(q),w.orderByDesc(ImBotEntity::getId)).convert(b->botView(b,admin || application));
    }
    @Override
    public ImBotDTO detail(Long key, boolean includeMcpUrls) {
        ImBotEntity bot = accessibleBot(key, true, false);
        ImBotDTO result = botView(bot, true);
        if (includeMcpUrls && Platform.WECOM.getCode().equals(bot.getPlatform())
                && bot.getTenantId().equals(AdminTenantContext.queryTenant()) && canReadMcpUrls(bot)) {
            result.setMcpUrls(configuredMcpUrls(bot));
        }
        describeActiveWecomIdentity(bot, result, true);
        return result;
    }

    @Override public ImBotDTO applicationDetail(Long key) {
        ImBotEntity bot = application(key);
        ImBotDTO result = botView(bot, true);
        ImBotUserEntity binding = user(key, UserInfoUtil.getUserId());
        result.setAuthorized(binding != null && yes(binding.getAuthorized()));
        result.setBound(binding != null && binding.getExternalUserKey() != null);
        result.setSecret(AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment), bot.getSecretCipher()));
        if (Platform.WECOM.getCode().equals(bot.getPlatform())) {
            result.setMcpUrls(ImBotMcpConfig.readApplication(bot.getDocumentConfigCipher(), ImBotTakeoverConfig.user(bot), environment));
            describeActiveWecomIdentity(bot, result, false);
        }
        return result;
    }

    private void describeActiveWecomIdentity(ImBotEntity bot, ImBotDTO result, boolean admin) {
        if (!Platform.WECOM.getCode().equals(bot.getPlatform())) return;
        ImBotEntity active = activeWecomIdentity(bot, false);
        if (active == null) return;
        result.setActiveIdentityMode(ImBotTakeoverConfig.user(active) ? Identity.USER.getCode() : Identity.BOT.getCode());
        if (admin) {
            result.setActiveBotKey(active.getId());
            result.setActiveBotConfigVersion(active.getConfigVersion());
            if (ImBotTakeoverConfig.user(active)) {
                result.setActiveOwnerUserId(ImBotTakeoverConfig.read(active, environment).getStr("ownerUserId"));
            }
        }
    }

    /** 启用校验使用加锁读，避免事务在获得原机器人行锁前建立的查询快照漏掉并发启用。 */
    private ImBotEntity activeWecomIdentity(ImBotEntity bot, boolean forEnable) {
        Long rootId = bot.getParentBotId() == null ? bot.getId() : bot.getParentBotId();
        return bots.selectOne(new LambdaQueryWrapper<ImBotEntity>()
                .eq(ImBotEntity::getPlatform, Platform.WECOM.getCode())
                .eq(ImBotEntity::getTenantId, bot.getTenantId()).eq(ImBotEntity::getEnabled, flag(true))
                .ne(forEnable, ImBotEntity::getId, bot.getId())
                .and(scope -> scope.eq(ImBotEntity::getId, rootId).or().eq(ImBotEntity::getParentBotId, rootId))
                .orderByAsc(ImBotEntity::getId).last(forEnable ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    private ImBotEntity application(Long key) {
        ImBotEntity bot = accessibleBot(key, false, false);
        if (bot.getParentBotId() != null) throw BizException.paramError("请选择应用公共配置");
        return bot;
    }

    /** 运行中的工具在本人配置缺少对应授权时读取应用的公共地址。 */
    Map<String, String> toolMcpUrls(ImBotEntity bot) {
        Map<String, String> urls = configuredMcpUrls(bot);
        if (ImBotTakeoverConfig.user(bot)) {
            ImBotEntity root = bot.getParentBotId() == null ? bot : bots.selectById(bot.getParentBotId());
            ImBotMcpConfig.readApplication(root.getDocumentConfigCipher(), ImBotTakeoverConfig.user(root), environment)
                    .forEach(urls::putIfAbsent);
        }
        return urls;
    }

    private Map<String, String> configuredMcpUrls(ImBotEntity bot) {
        return ImBotTakeoverConfig.user(bot) ? ImBotMcpConfig.read(bot.getDocumentConfigCipher(), environment)
                : ImBotMcpConfig.readApplication(bot.getDocumentConfigCipher(), false, environment);
    }

    /** 普通机器人的地址由本租户管理员管理，真人地址要求当前账号仍有有效绑定。 */
    private boolean canReadMcpUrls(ImBotEntity bot) {
        if (!ImBotTakeoverConfig.user(bot)) return true;
        cn.hutool.json.JSONObject config = ImBotTakeoverConfig.read(bot, environment);
        String userId = UserInfoUtil.getUserId();
        if (!userId.equals(config.getStr("ownerUserId"))) return false;
        ImBotUserEntity binding = user(bot.getId(), userId);
        return binding != null && yes(binding.getAuthorized()) && StringUtils.isNotBlank(binding.getExternalUserId())
                && binding.getExternalUserId().equals(config.getStr("externalUserId"));
    }
    private ImBotDTO botView(ImBotEntity b, boolean admin) {
        ImBotDTO v=new ImBotDTO();
        if(admin) { BeanUtils.copyProperties(b,v); v.setAllowedGroupChatIds(groups(b)); v.setHasSecret(StringUtils.isNotBlank(b.getSecretCipher())); v.setDocumentConfigured(Platform.FEISHU.getCode().equals(b.getPlatform()) || StringUtils.isNotBlank(b.getDocumentConfigCipher())); }
        v.setBotKey(b.getId()); v.setPlatform(b.getPlatform()); v.setName(b.getName()); v.setEnabled(yes(b.getEnabled()));
        v.setIdentityMode(StringUtils.defaultIfBlank(b.getIdentityMode(),Identity.BOT.getCode()));
        v.setAutoProcessEnabled(yes(b.getAutoProcessEnabled())); v.setGroupEnabled(yes(b.getGroupEnabled()));
        v.setConfiguredCapabilities(new java.util.ArrayList<>(configuredMcpUrls(b).keySet()));
        v.setDocumentConfigured(Platform.FEISHU.getCode().equals(b.getPlatform()) || v.getConfiguredCapabilities().contains(cn.com.servyou.work.api.enums.ImBotMcpCapability.DOCUMENT.getCode()));
        v.setConnectionState(b.getConnectionState()); v.setSupportedContentTypes(Platform.DINGTALK.getCode().equals(b.getPlatform()) ? Collections.singletonList(Content.TEXT.getCode()) : Platform.WECOM.getCode().equals(b.getPlatform())
                ? Arrays.asList(Content.TEXT.getCode(),Content.MARKDOWN.getCode(),Content.FILE.getCode()) : Arrays.asList(Content.TEXT.getCode(),Content.FILE.getCode()));
        if(!admin) {
            ImBotUserEntity u=user(b.getId(),UserInfoUtil.getUserId());
            v.setAuthorized(u!=null && yes(u.getAuthorized())); v.setBound(u!=null && u.getExternalUserKey()!=null);
            if(!v.getEnabled()) v.setUnavailableReason("机器人已停用"); else if(!Boolean.TRUE.equals(v.getBound())) v.setUnavailableReason("请先单聊绑定");
        }
        return v;
    }

    @Override public ImBotDTO updateApplication(ImBotSaveDTO request) {
        text(request.getName(), "name", 128);
        if (request.getDescription() != null && request.getDescription().length() > 1000) throw BizException.paramError("description过长");
        Long version = request.getExpectedConfigVersion();
        if (version == null || version == Long.MAX_VALUE) throw BizException.paramError("expectedConfigVersion不合法");
        return tx.execute(status -> {
            ImBotEntity bot = lockBot(application(request.getBotKey()).getId());
            if (!version.equals(bot.getConfigVersion())) throw new BizException(Error.VERSION_CONFLICT.getCode(), "配置已被修改，请刷新");
            ImBotDTO before = botView(bot, true);
            String previousSecret = bot.getSecretCipher();
            if (request.getSecret() != null) {
                String value = text(request.getSecret(), "secret", 512);
                if (!value.equals(AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment), bot.getSecretCipher()))) {
                    bot.setSecretCipher(AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(environment), value));
                }
            }
            boolean secretChanged = !Objects.equals(bot.getSecretCipher(), previousSecret);
            if (request.getMcpUrls() != null) {
                if (!Platform.WECOM.getCode().equals(bot.getPlatform())) throw BizException.paramError("只有企微机器人使用MCP能力授权地址");
                bot.setDocumentConfigCipher(ImBotMcpConfig.mergeApplication(bot.getDocumentConfigCipher(),
                        ImBotTakeoverConfig.user(bot), null, request.getMcpUrls(), environment));
            }
            boolean autoChanged = request.getAutoProcessEnabled() != null
                    && flag(request.getAutoProcessEnabled()) != bot.getAutoProcessEnabled();
            boolean groupChanged = request.getGroupEnabled() != null && flag(request.getGroupEnabled()) != bot.getGroupEnabled();
            LambdaUpdateWrapper<ImBotEntity> update = new LambdaUpdateWrapper<ImBotEntity>()
                    .eq(ImBotEntity::getId, bot.getId()).eq(ImBotEntity::getConfigVersion, version)
                    .set(ImBotEntity::getName, request.getName()).set(ImBotEntity::getDescription, request.getDescription())
                    .set(ImBotEntity::getSecretCipher, bot.getSecretCipher()).set(ImBotEntity::getDocumentConfigCipher, bot.getDocumentConfigCipher())
                    .set(autoChanged, ImBotEntity::getAutoProcessEnabled, flag(request.getAutoProcessEnabled()))
                    .set(groupChanged, ImBotEntity::getGroupEnabled, flag(request.getGroupEnabled()))
                    .set(ImBotEntity::getConfigVersion, version + 1).set(ImBotEntity::getUpdateUserId, UserInfoUtil.getUserId());
            if (request.getAllowedGroupChatIds() != null) {
                update.set(ImBotEntity::getAllowedGroupChatIds, JSONUtil.toJsonStr(
                        checkedGroups(bot, request.getAllowedGroupChatIds(), false)));
            }
            if (bots.update(null, update) != 1) throw new BizException(Error.VERSION_CONFLICT.getCode(), "配置已被修改，请刷新");
            if (autoChanged || groupChanged || secretChanged) {
                bots.update(null, new LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getParentBotId, bot.getId())
                        .set(autoChanged, ImBotEntity::getAutoProcessEnabled, flag(request.getAutoProcessEnabled()))
                        .set(groupChanged, ImBotEntity::getGroupEnabled, flag(request.getGroupEnabled()))
                        .setSql("config_version = config_version + 1").set(ImBotEntity::getUpdateUserId, UserInfoUtil.getUserId()));
            }
            ImBotEntity saved = bots.selectById(bot.getId());
            ImBotDTO after = botView(saved, true);
            log(AdminOpActionEnum.UPDATE, saved, before, after, "应用公共配置");
            return after;
        });
    }

    private List<String> checkedGroups(ImBotEntity bot, List<String> allowed, boolean create) {
        if (allowed.size() > 100) throw BizException.paramError("允许群最多100个");
        List<String> existing = groups(bot);
        for (String chat : allowed) {
            text(chat, "allowedGroupChatIds", 256);
            if (create || !existing.contains(chat) && sessions.selectCount(new LambdaQueryWrapper<ImBotSessionEntity>()
                    .eq(ImBotSessionEntity::getBotId, bot.getId()).eq(ImBotSessionEntity::getChatKey, hash(chat))
                    .eq(ImBotSessionEntity::getSessionType, SessionType.GROUP.getCode())) == 0)
                throw BizException.paramError("仅可选择已有互动的群");
        }
        return allowed;
    }
    @Override public ImBotDTO save(ImBotSaveDTO r, boolean create) {
        scope(true); String tenant=AdminTenantContext.requireTarget();
        text(r.getName(),"name",128); if(r.getDescription()!=null && r.getDescription().length()>1000) throw BizException.paramError("description过长");
        ImBotDTO result=tx.execute(status->{
            ImBotEntity b=create?new ImBotEntity():accessibleBot(r.getBotKey(),true,true);
            ImBotDTO before=create?null:botView(b,true);
            if(create) {
                require(Platform.class,r.getPlatform()); text(r.getExternalAppId(),"externalAppId",128);
                b.setId(IdWorker.getId()); b.setTenantId(tenant); b.setPlatform(r.getPlatform()); b.setExternalAppId(r.getExternalAppId());
                b.setAppIdKey(hash(r.getExternalAppId())); b.setEnabled(flag(false)); b.setConfigVersion(1L); b.setOwnerVersion(0L);
                b.setIdentityMode(Identity.BOT.getCode());
                b.setConnectionState(Connection.DISABLED.getCode()); b.setCreateUserId(UserInfoUtil.getUserId());
            } else if(r.getPlatform()!=null && !r.getPlatform().equals(b.getPlatform()) || !create && r.getExternalAppId()!=null && !r.getExternalAppId().equals(b.getExternalAppId())) throw BizException.paramError("平台及外部应用标识不可修改");
            if(b.getParentBotId()!=null && r.getSecret()!=null) throw BizException.paramError("请在原机器人配置应用密钥");
            if(create || r.getSecret()!=null) b.setSecretCipher(AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(environment),text(r.getSecret(),"secret",512)));
            if(r.getDocumentMcpUrl()!=null || r.getMcpUrls()!=null) {
                if(!Platform.WECOM.getCode().equals(b.getPlatform()))throw BizException.paramError("只有企微机器人使用MCP能力授权地址");
                b.setDocumentConfigCipher(ImBotTakeoverConfig.user(b) || b.getParentBotId()!=null
                        ? ImBotMcpConfig.merge(b.getDocumentConfigCipher(), r.getDocumentMcpUrl(), r.getMcpUrls(), environment)
                        : ImBotMcpConfig.mergeApplication(b.getDocumentConfigCipher(), false, r.getDocumentMcpUrl(), r.getMcpUrls(), environment));
            }
            List<String> allowed=r.getAllowedGroupChatIds()==null?Collections.emptyList():r.getAllowedGroupChatIds();
            checkedGroups(b, allowed, create);
            b.setName(r.getName()); b.setDescription(r.getDescription());
            b.setAutoProcessEnabled(flag(r.getAutoProcessEnabled())); b.setGroupEnabled(flag(r.getGroupEnabled())); b.setAllowedGroupChatIds(JSONUtil.toJsonStr(allowed));
            b.setUpdateUserId(UserInfoUtil.getUserId());
            try {
                if(create) bots.insert(b);
                else {
                    if(r.getExpectedConfigVersion()==null || r.getExpectedConfigVersion()==Long.MAX_VALUE) throw BizException.paramError("expectedConfigVersion不合法");
                    // 只写配置字段，不用全量 Entity 覆盖 owner 心跳。
                    int count=bots.update(null,new LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId())
                            .eq(ImBotEntity::getConfigVersion,r.getExpectedConfigVersion()).set(ImBotEntity::getConfigVersion,r.getExpectedConfigVersion()+1)
                            .set(ImBotEntity::getName,b.getName()).set(ImBotEntity::getDescription,b.getDescription())
                            .set(r.getSecret()!=null,ImBotEntity::getSecretCipher,b.getSecretCipher()).set(ImBotEntity::getDocumentConfigCipher,b.getDocumentConfigCipher()).set(ImBotEntity::getAutoProcessEnabled,b.getAutoProcessEnabled())
                            .set(ImBotEntity::getGroupEnabled,b.getGroupEnabled()).set(ImBotEntity::getAllowedGroupChatIds,b.getAllowedGroupChatIds())
                            .set(ImBotEntity::getUpdateUserId,b.getUpdateUserId()));
                    if(count!=1) throw new BizException(ImBotEnums.Error.VERSION_CONFLICT.getCode(),"配置已被修改，请刷新");
                    b.setConfigVersion(r.getExpectedConfigVersion()+1);
                }
            } catch(DuplicateKeyException e) { throw new BizException(BizException.CODE_PARAM_ERROR,"此平台应用已配置",e); }
            ImBotDTO after=botView(b,true); log(create?AdminOpActionEnum.CREATE:AdminOpActionEnum.UPDATE,b,before,after,null); return after;
        });
        return result;
    }
    @Override public ImBotDTO enable(ImBotOperationDTO r, boolean enabled) {
        return tx.execute(status->{
            ImBotEntity b=accessibleBot(r.getBotKey(),true,true);
            if(r.getExpectedConfigVersion()==null || r.getExpectedConfigVersion()==Long.MAX_VALUE) throw BizException.paramError("expectedConfigVersion不合法");
            if (Platform.WECOM.getCode().equals(b.getPlatform())) {
                ImBotEntity root = lockBot(b.getParentBotId() == null ? b.getId() : b.getParentBotId());
                if (b.getParentBotId() == null) b = root;
                else {
                    b = lockBot(b.getId());
                    b.setSecretCipher(root.getSecretCipher());
                }
                if (!r.getExpectedConfigVersion().equals(b.getConfigVersion()))
                    throw new BizException(Error.VERSION_CONFLICT.getCode(), "配置已被修改，请刷新");
                ImBotEntity conflict = enabled ? activeWecomIdentity(b, true) : null;
                if (conflict != null) {
                    log.warn("企微启用身份冲突 botKey={}, activeBotKey={}, activeIdentityMode={}, operator={}",
                            b.getId(), conflict.getId(), conflict.getIdentityMode(), UserInfoUtil.getUserId());
                    throw new BizException(Error.WECOM_IDENTITY_CONFLICT.getCode(),
                            "同一企微 BotID 已有启用配置，请先停用当前身份再切换");
                }
            }
            ImBotDTO before=botView(b,true);
            if(enabled && ImBotTakeoverConfig.user(b)) {
                cn.hutool.json.JSONObject c=ImBotTakeoverConfig.read(b,environment);ImBotUserEntity u=user(b.getId(),c.getStr("ownerUserId"));
                if(u==null || !yes(u.getAuthorized()) || !Objects.equals(u.getExternalUserId(),c.getStr("externalUserId")))throw BizException.noPermission("接管本人未授权或绑定已变化");
                if(Platform.DINGTALK.getCode().equals(b.getPlatform()) && !DingTalkUserAuthorization.ready(c))
                    throw BizException.paramError("请先完成钉钉本人设备授权");
                ImBotTakeoverConfig.messageUserId(b,c);
                if(!yes(b.getAutoProcessEnabled()))throw BizException.paramError("真人接管需先打开autoProcessEnabled");
                if(Platform.FEISHU.getCode().equals(b.getPlatform()) && (StringUtils.isBlank(c.getStr("refreshToken")) || c.getLong("refreshExpiresAt",0L)<=System.currentTimeMillis()))
                    throw BizException.paramError("请先完成飞书本人OAuth授权");
                if(Platform.WECOM.getCode().equals(b.getPlatform()) && !ImBotMcpConfig.read(b.getDocumentConfigCipher(),environment).containsKey(ImBotMcpCapability.MESSAGE.getCode()))
                    throw BizException.paramError("请先配置企微消息MCP本人授权");
            }
            String takeover=enabled?ImBotTakeoverConfig.activate(b,environment):b.getTakeoverConfigCipher();
            int count=bots.update(null,new LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,b.getId()).eq(ImBotEntity::getConfigVersion,r.getExpectedConfigVersion())
                    .set(ImBotEntity::getEnabled,flag(enabled)).set(ImBotEntity::getTakeoverConfigCipher,takeover)
                    .set(ImBotEntity::getLastError,null).set(ImBotEntity::getConfigVersion,r.getExpectedConfigVersion()+1).set(ImBotEntity::getUpdateUserId,UserInfoUtil.getUserId()));
            if(count!=1) throw new BizException(ImBotEnums.Error.VERSION_CONFLICT.getCode(),"配置已被修改，请刷新");
            if(!enabled) cancelPending(b.getId(),null);
            b.setEnabled(flag(enabled)); b.setConfigVersion(r.getExpectedConfigVersion()+1);
            b.setTakeoverConfigCipher(takeover);
            ImBotDTO after=botView(b,true); log(enabled?AdminOpActionEnum.ENABLE:AdminOpActionEnum.DISABLE,b,before,after,r.getReason()); return after;
        });
    }
    @Override public IPage<ImBotUserDTO> pageUsers(ImBotQueryDTO q) {
        ImBotEntity b=accessibleBot(q.getBotKey(),true,false);
        if(q.getAuthorized()!=null && !BinaryFlagEnum.contains(q.getAuthorized())) throw BizException.paramError("authorized不合法");
        LambdaQueryWrapper<ImBotUserEntity> w=new LambdaQueryWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getBotId,b.getId())
                .eq(q.getAuthorized()!=null,ImBotUserEntity::getAuthorized,q.getAuthorized()).like(StringUtils.isNotBlank(q.getKeyword()),ImBotUserEntity::getUserName,q.getKeyword());
        if(q.getBound()!=null) { if(q.getBound()) w.isNotNull(ImBotUserEntity::getExternalUserKey); else w.isNull(ImBotUserEntity::getExternalUserKey); }
        return users.selectPage(ImBotServiceImpl.<ImBotUserEntity>page(q),w.orderByDesc(ImBotUserEntity::getId)).convert(u->{
            ImBotUserDTO v=new ImBotUserDTO(); BeanUtils.copyProperties(u,v); v.setBotKey(u.getBotId()); v.setBound(u.getExternalUserKey()!=null); v.setAuthorized(yes(u.getAuthorized())); return v;
        });
    }
    @Override public void grant(ImBotOperationDTO r, boolean grant) {
        ImBotEntity b=accessibleBot(r.getBotKey(),true,true); text(r.getTargetUserId(),"targetUserId",64);
        if (b.getParentBotId()!=null) throw BizException.paramError("请在原机器人管理人员绑定");
        String name=grant?members.requireMember(b.getTenantId(),r.getTargetUserId()).getSwrymc():null;
        tx.executeWithoutResult(status->{
            lockBot(b.getId()); ImBotUserEntity u=user(b.getId(),r.getTargetUserId());
            if(u==null && grant) { u=new ImBotUserEntity(); u.setBotId(b.getId()); u.setTenantId(b.getTenantId()); u.setUserId(r.getTargetUserId()); u.setUserName(name); u.setAuthorized(flag(true)); users.insert(u); }
            else if(u!=null) {
                LambdaUpdateWrapper<ImBotUserEntity> w=new LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,u.getId()).set(ImBotUserEntity::getAuthorized,flag(grant));
                if(grant) w.set(ImBotUserEntity::getUserName,name); else clearBinding(w);
                users.update(null,w);
            }
            if(!grant) {
                if (u!=null) retireProfile(u);
                cancelPending(b.getId(),r.getTargetUserId());
                removeTargets(b.getId(),r.getTargetUserId());
            }
            log(grant?AdminOpActionEnum.ENABLE:AdminOpActionEnum.DISABLE,b,null,Collections.singletonMap("userId",r.getTargetUserId()),r.getReason());
        });
    }
    @Override public ImBotBindingCodeDTO createCode(Long key) {
        ImBotEntity b=accessibleBot(key,false,false); requireUser(b,false);
        if(!yes(b.getEnabled())) throw BizException.paramError("机器人已停用");
        accounts.ensurePortalLongToken(b.getTenantId(),UserInfoUtil.getUserId(),UserInfoUtil.getAccountToken());
        String code=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        return tx.execute(status->{
            lockBot(key); b.setEnabled(bot(key).getEnabled()); if(!yes(b.getEnabled())) throw BizException.paramError("机器人已停用");
            ImBotUserEntity u=requireUser(b,false); if(u.getExternalUserKey()!=null) throw BizException.paramError("已绑定，重新绑定前请先解绑");
            LocalDateTime expiry=bots.databaseNow().plusMinutes(5);
            users.update(null,new LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,u.getId()).eq(ImBotUserEntity::getAuthorized,flag(true))
                    .set(ImBotUserEntity::getBindingCodeHash,hash(code)).set(ImBotUserEntity::getBindingCodeExpiresAt,expiry));
            ImBotBindingCodeDTO v=new ImBotBindingCodeDTO(); v.setCode(code); v.setBindingText("绑定 "+code); v.setExpiresAt(expiry); return v;
        });
    }
    @Override public void unbind(Long key) {
        ImBotEntity b=accessibleBot(key,false,false);
        tx.executeWithoutResult(status->{ lockBot(key); ImBotUserEntity u=requireUser(b,false);
            users.update(null,clearBinding(new LambdaUpdateWrapper<ImBotUserEntity>().eq(ImBotUserEntity::getId,u.getId())));
            retireProfile(u);
            cancelPending(key,u.getUserId());
            removeTargets(key,u.getUserId());
        });
    }
    /** 撤权或解绑即停用本人配置并清除个人凭据。 */
    private void retireProfile(ImBotUserEntity u) {
        ImBotEntity p=bots.selectById(u.getId());
        if(p==null || !u.getBotId().equals(p.getParentBotId())) return;
        cn.hutool.json.JSONObject cleared=cn.hutool.json.JSONUtil.createObj().set("ownerUserId",u.getUserId());
        bots.update(null,new LambdaUpdateWrapper<ImBotEntity>().eq(ImBotEntity::getId,p.getId())
                .set(ImBotEntity::getEnabled,flag(false)).set(ImBotEntity::getConnectionState,Connection.DISABLED.getCode())
                .set(ImBotEntity::getDocumentConfigCipher,null).set(ImBotEntity::getTakeoverConfigCipher,ImBotTakeoverConfig.encrypt(cleared,environment))
                .setSql("config_version=config_version+1"));
        cancelPending(p.getId(),u.getUserId());
    }
    private LambdaUpdateWrapper<ImBotUserEntity> clearBinding(LambdaUpdateWrapper<ImBotUserEntity> w) {
        return w.set(ImBotUserEntity::getExternalUserId,null).set(ImBotUserEntity::getExternalUserKey,null).set(ImBotUserEntity::getBindingCodeHash,null)
                .set(ImBotUserEntity::getBindingCodeExpiresAt,null).set(ImBotUserEntity::getBoundAt,null);
    }
    private void removeTargets(Long key,String userId) {
        // 外部身份重新绑定后必须重新观察目标，避免继续向原身份的单聊或群发送。
        // 消息表保留原 sessionId 和正文，历史无需依赖目标映射存续。
        sessions.delete(new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,key)
                .eq(ImBotSessionEntity::getUserId,userId));
    }
    ImBotEntity lockBot(Long key) {
        return bots.selectOne(new LambdaQueryWrapper<ImBotEntity>().eq(ImBotEntity::getId,key).last("FOR UPDATE"));
    }
    void cancelPending(Long key,String user) {
        messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,key).eq(user!=null,ImBotMessageEntity::getUserId,user)
                .eq(ImBotMessageEntity::getProcessState,ImBotEnums.Process.QUEUED.getCode()).set(ImBotMessageEntity::getProcessState,ImBotEnums.Process.CANCELED.getCode()));
        messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,key).eq(user!=null,ImBotMessageEntity::getUserId,user)
                .in(ImBotMessageEntity::getProcessState,ImBotEnums.Process.RUNNING.getCode(),ImBotEnums.Process.WAITING_INPUT.getCode(),ImBotEnums.Process.RECOVERING.getCode())
                .set(ImBotMessageEntity::getProcessState,ImBotEnums.Process.CANCEL_REQUESTED.getCode()));
        messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getBotId,key).eq(user!=null,ImBotMessageEntity::getUserId,user)
                .eq(ImBotMessageEntity::getSendState,Send.PENDING.getCode()).set(ImBotMessageEntity::getSendState,Send.CANCELED.getCode()));
    }
    @Override public IPage<ImBotSessionDTO> pageSessions(ImBotQueryDTO q,boolean admin) {
        ImBotEntity b=accessibleBot(q.getBotKey(),admin,false); if(!admin) requireUser(b,true);
        if(q.getSessionType()!=null) require(SessionType.class,q.getSessionType());
        return sessions.selectPage(ImBotServiceImpl.<ImBotSessionEntity>page(q),new LambdaQueryWrapper<ImBotSessionEntity>().eq(ImBotSessionEntity::getBotId,b.getId())
                .eq(!admin,ImBotSessionEntity::getUserId,admin?null:UserInfoUtil.getUserId()).eq(q.getSessionType()!=null,ImBotSessionEntity::getSessionType,q.getSessionType())
                .like(StringUtils.isNotBlank(q.getKeyword()),ImBotSessionEntity::getName,q.getKeyword()).orderByDesc(ImBotSessionEntity::getLastInteractionAt).orderByDesc(ImBotSessionEntity::getId))
                .convert(s->{ImBotSessionDTO v=new ImBotSessionDTO(); BeanUtils.copyProperties(s,v); v.setTargetSessionKey(s.getId()); v.setBotKey(s.getBotId()); v.setSendable(sendable(b,s)); if(!admin){v.setExternalChatId(null);v.setUserId(null);} return v;});
    }
    @Override public IPage<ImBotMessageDTO> pageMessages(ImBotQueryDTO q,boolean admin) {
        String tenant=scope(admin);
        if(q.getStartTime()!=null && q.getEndTime()!=null && q.getStartTime().isAfter(q.getEndTime())) throw BizException.paramError("开始时间不能晚于结束时间");
        if(q.getDirection()!=null) require(Direction.class,q.getDirection());
        if(q.getProcessState()!=null) require(ImBotEnums.Process.class,q.getProcessState());
        if(q.getSendState()!=null) require(Send.class,q.getSendState());
        if(q.getPlatform()!=null) require(Platform.class,q.getPlatform());
        LambdaQueryWrapper<ImBotMessageEntity> w=new LambdaQueryWrapper<ImBotMessageEntity>().eq(tenant!=null,ImBotMessageEntity::getTenantId,tenant)
                .eq(!admin,ImBotMessageEntity::getUserId,admin?null:UserInfoUtil.getUserId()).eq(admin && q.getTargetUserId()!=null,ImBotMessageEntity::getUserId,q.getTargetUserId())
                .eq(q.getBotKey()!=null,ImBotMessageEntity::getBotId,q.getBotKey()).eq(q.getDirection()!=null,ImBotMessageEntity::getDirection,q.getDirection())
                .eq(q.getProcessState()!=null,ImBotMessageEntity::getProcessState,q.getProcessState()).eq(q.getSendState()!=null,ImBotMessageEntity::getSendState,q.getSendState())
                .ge(q.getStartTime()!=null,ImBotMessageEntity::getCreateTime,q.getStartTime()).le(q.getEndTime()!=null,ImBotMessageEntity::getCreateTime,q.getEndTime());
        if(q.getPlatform()!=null) w.apply("bot_id IN (SELECT id FROM sw_im_bot WHERE platform={0})",q.getPlatform());
        return messages.selectPage(ImBotServiceImpl.<ImBotMessageEntity>page(q),w.orderByDesc(ImBotMessageEntity::getId)).convert(m->messageView(m,admin,false));
    }
    @Override public ImBotMessageDTO messageDetail(Long id,boolean admin) { return messageView(accessibleMessage(id,admin),admin,true); }
    private ImBotMessageEntity accessibleMessage(Long id,boolean admin) {
        scope(admin); if(id==null) throw BizException.paramError("messageId必填");
        ImBotMessageEntity m=messages.selectById(id);
        if(m==null || admin && !AdminTenantContext.includes(m.getTenantId()) || !admin && (!UserInfoUtil.getTenantId().equals(m.getTenantId()) || !UserInfoUtil.getUserId().equals(m.getUserId())))
            throw BizException.noPermission("消息不存在或无权访问");
        return m;
    }
    ImBotMessageDTO messageView(ImBotMessageEntity m, boolean admin, boolean body) {
        ImBotMessageDTO v=new ImBotMessageDTO(); BeanUtils.copyProperties(m,v); v.setMessageId(m.getId()); v.setBotKey(m.getBotId()); v.setTargetSessionKey(m.getTargetSessionId()); v.setCreatedAt(m.getCreateTime()); v.setUpdatedAt(m.getUpdateTime()); v.setPlatform(bot(m.getBotId()).getPlatform());
        if(!body) {v.setContent(null);v.setReplyContent(null);} if(!admin){v.setUserId(null);v.setRunId(null);v.setDbMessageId(null);v.setProviderMessageId(null);} return v;
    }
    @Override public ImBotMessageDTO send(ImBotSendDTO r) {
        text(r.getRequestId(),"requestId",128); require(Content.class,r.getContentType());
        ImBotEntity b=accessibleBot(r.getBotKey(),false,false);
        if(ImBotTakeoverConfig.user(b))throw BizException.paramError("真人接管由后端自动回复；此入口用于BOT模式，不能混用机器人身份发送");
        boolean file=Content.FILE.getCode().equals(r.getContentType());
        cn.com.servyou.work.api.model.document.FileDownloadDTO source;
        if(file) {
            if (Platform.DINGTALK.getCode().equals(b.getPlatform())) throw BizException.paramError("钉钉机器人当前支持文本发送");
            if(StringUtils.isBlank(r.getSourceSessionId()) || r.getConversationFileId()==null)throw BizException.paramError("FILE须指定sourceSessionId和conversationFileId");
            source=conversations.getConversationDownloadFile(r.getSourceSessionId(),r.getConversationFileId());
        } else source=null;
        String digest=hash(JSONUtil.toJsonStr(file?Arrays.asList(r.getTargetSessionKey(),r.getContentType(),r.getContent(),r.getSourceSessionId(),r.getConversationFileId())
                :Arrays.asList(r.getTargetSessionKey(),r.getContentType(),r.getContent())));
        return tx.execute(status->{
            lockBot(b.getId()); ImBotEntity current=bot(b.getId());
            ImBotMessageEntity old=messages.selectOne(new LambdaQueryWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getTenantId,b.getTenantId())
                    .eq(ImBotMessageEntity::getUserId,UserInfoUtil.getUserId()).eq(ImBotMessageEntity::getBotId,b.getId()).eq(ImBotMessageEntity::getRequestId,r.getRequestId()));
            if(old!=null) {if(!digest.equals(old.getRequestHash())) throw new BizException(ImBotEnums.Error.IDEMPOTENCY_CONFLICT.getCode(),"相同requestId的目标或正文不同");return messageView(old,false,true);}
            ImBotSessionEntity s=sessions.selectById(r.getTargetSessionKey()); requireUser(current,true);
            if(s==null || !UserInfoUtil.getUserId().equals(s.getUserId()) || !sendable(current,s)) throw BizException.noPermission("目标不可发送");
            if(!file)ImBotContent.validate(b.getPlatform(),r.getContentType(),r.getContent());
            else {
                long limit=Platform.WECOM.getCode().equals(b.getPlatform())?20L*1024*1024:30L*1024*1024;
                if(source.getFileSize()==null || source.getFileSize()>limit)throw new BizException(ImBotEnums.Error.FILE_TOO_LARGE.getCode(),"附件超过平台发送限制");
            }
            ImBotMessageEntity m=new ImBotMessageEntity(); m.setBotId(b.getId()); m.setTenantId(b.getTenantId()); m.setUserId(UserInfoUtil.getUserId()); m.setTargetSessionId(s.getId());
            m.setDirection(Direction.OUTBOUND.getCode()); m.setContentType(r.getContentType()); m.setContent(r.getContent()); m.setRequestId(r.getRequestId()); m.setRequestHash(digest);
            if(file) {
                m.setFileId(source.getFileId());m.setSessionId(r.getSourceSessionId());m.setContent(source.getFileName());
                m.setReplyContext(JSONUtil.createObj().set("sourceConversationFileId",String.valueOf(r.getConversationFileId())).toString());
            }
            m.setProviderUuid(UUID.randomUUID().toString());m.setSendState(Send.PENDING.getCode());m.setCreateTime(bots.databaseNow());m.setExpiresAt(m.getCreateTime().plusMinutes(10)); messages.insert(m); return messageView(m,false,true);
        });
    }
    @Override public ImBotMessageDTO cancel(Long id,String reason,boolean admin) {
        ImBotMessageDTO result=tx.execute(status->{
            ImBotMessageEntity m=accessibleMessage(id,admin); ImBotEntity b=accessibleBot(m.getBotId(),admin,admin); lockBot(b.getId());
            messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getId,id).eq(ImBotMessageEntity::getProcessState,ImBotEnums.Process.QUEUED.getCode()).set(ImBotMessageEntity::getProcessState,ImBotEnums.Process.CANCELED.getCode()));
            messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getId,id).in(ImBotMessageEntity::getProcessState,ImBotEnums.Process.RUNNING.getCode(),ImBotEnums.Process.WAITING_INPUT.getCode(),ImBotEnums.Process.RECOVERING.getCode()).set(ImBotMessageEntity::getProcessState,ImBotEnums.Process.CANCEL_REQUESTED.getCode()));
            messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getId,id).eq(ImBotMessageEntity::getSendState,Send.PENDING.getCode()).set(ImBotMessageEntity::getSendState,Send.CANCELED.getCode()));
            if(admin) log(AdminOpActionEnum.TERMINATE,b,null,Collections.singletonMap("messageId",String.valueOf(id)),reason);
            return messageView(messages.selectById(id),admin,true);
        });
        ImBotMessageEntity current=messages.selectById(id);
        if(conversations!=null && ImBotEnums.Process.CANCEL_REQUESTED.getCode().equals(current.getProcessState())
                && (current.getDbMessageId()!=null || current.getRunId()!=null)) {
            cn.com.servyou.work.api.model.conversation.ReqStopConversationDTO stop=new cn.com.servyou.work.api.model.conversation.ReqStopConversationDTO();
            stop.setSessionId(current.getSessionId());stop.setExpectedMessageId(current.getDbMessageId());stop.setExpectedRunId(current.getRunId());
            try {
                if(admin) conversations.stopConversationAsAdministrator(stop,current.getTenantId());
                else conversations.stopConversation(stop);
            } catch(Exception unavailable) {
                log.warn("机器人消息停止失败，等待后台核查 botKey={}, messageId={}, sessionId={}, requestId={}\n{}",
                        current.getBotId(),id,current.getSessionId(),current.getRequestId(),safeStackTrace(unavailable));
                messages.update(null,new LambdaUpdateWrapper<ImBotMessageEntity>().eq(ImBotMessageEntity::getId,id)
                        .eq(ImBotMessageEntity::getProcessState,ImBotEnums.Process.CANCEL_REQUESTED.getCode()).set(ImBotMessageEntity::getLastError,"停止请求待后台继续核查"));
            }
        }
        return result;
    }
    private void log(AdminOpActionEnum action,ImBotEntity b,Object before,Object after,String reason) {
        audit.successRequired(AdminOpModuleEnum.IM_BOT,action,AdminOpTargetTypeEnum.IM_BOT,String.valueOf(b.getId()),b.getName(),before,after,limited(reason,512));
    }
}
