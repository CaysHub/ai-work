package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 长连接发送与文档接口共用的飞书应用凭据缓存，不新增持久化或刷新锁。 */
final class ImBotFeishuAuth {
    /** 缓存键包含密钥摘要，机器人更换密钥后不复用旧认证结果。 */
    private static final Cache<String,JSONObject> TOKENS=CacheBuilder.newBuilder().maximumSize(1000)
            .expireAfterWrite(2,TimeUnit.HOURS).build();
    private ImBotFeishuAuth() { }
    /** 使用现有应用身份认证流程，不在模型环境中传递平台 token。 */
    static Map<String,String> headers(String appId,String secret) throws Exception {
        String key=DigestUtil.sha256Hex(appId+":"+secret);
        JSONObject cached=TOKENS.getIfPresent(key);
        if(cached!=null && System.nanoTime()>=cached.getLong("deadline"))TOKENS.invalidate(key);
        JSONObject token;
        try { token=TOKENS.get(key,()->{
            JSONObject body=JSONUtil.createObj().set("grant_type","client_credentials").set("client_id",appId).set("client_secret",secret);
            OkHttpClientUtil.HttpResult result=OkHttpClientUtil.postJsonNoRetry("https://accounts.feishu.cn/oauth/v3/token",Collections.emptyMap(),body.toString());
            JSONObject response=JSONUtil.parseObj(result.getBody());Integer seconds=response.getInt("expires_in");
            if(!result.isSuccessful() || response.getStr("access_token")==null || seconds==null || seconds<=60)
                throw new BizException(Error.PLATFORM_AUTH_FAILED.getCode(),"飞书应用认证失败，请检查机器人配置");
            return JSONUtil.createObj().set("value",response.getStr("access_token"))
                    .set("deadline",System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds-60));
        }); } catch(java.util.concurrent.ExecutionException failure) {
            if(failure.getCause() instanceof BizException)throw (BizException)failure.getCause();
            throw failure;
        }
        return Collections.singletonMap("Authorization","Bearer "+token.getStr("value"));
    }
}
