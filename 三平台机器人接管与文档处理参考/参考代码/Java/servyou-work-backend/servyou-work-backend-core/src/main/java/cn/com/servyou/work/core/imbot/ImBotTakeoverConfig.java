package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Identity;
import cn.com.servyou.work.api.enums.ImBotEnums.Platform;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.com.servyou.work.dao.entity.*;
import cn.hutool.json.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import java.util.*;

/** 接管配置只保存在现有机器人行的加密字段，不建立另一套授权表或锁。 */
final class ImBotTakeoverConfig {
    private ImBotTakeoverConfig() { }
    static boolean user(ImBotEntity bot){return Identity.USER.getCode().equals(bot.getIdentityMode());}
    static boolean user(ImBotMessageEntity row){return Identity.USER.getCode().equals(context(row).getStr("identityMode"));}
    static JSONObject context(ImBotMessageEntity row){return JSONUtil.parseObj(StringUtils.defaultIfBlank(row.getReplyContext(),"{}"));}
    static JSONObject read(ImBotEntity bot,Environment env){return StringUtils.isBlank(bot.getTakeoverConfigCipher())?new JSONObject():JSONUtil.parseObj(AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(env),bot.getTakeoverConfigCipher()));}
    static String encrypt(JSONObject value,Environment env){return AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(env),value.toString());}
    static List<String> list(JSONObject config,String name){JSONArray a=config.getJSONArray(name);return a==null?Collections.emptyList():a.toList(String.class);}
    /** 企微使用配置的消息标识，钉钉使用核验过的开放钉钉号，飞书使用绑定标识。 */
    static String messageUserId(ImBotEntity bot,JSONObject config){
        String id=Platform.FEISHU.getCode().equals(bot.getPlatform())?config.getStr("externalUserId"):config.getStr("messageUserId");
        if(StringUtils.isBlank(id))throw BizException.paramError("接管配置缺少消息接口本人ID，请核对本人授权或消息标识");
        return ImBotServiceImpl.text(id,"messageUserId",256);
    }
    /** 每次重新启用建立独立代次，不追答停用期间或旧模式的历史。 */
    static String activate(ImBotEntity bot,Environment env){
        if(!user(bot))return bot.getTakeoverConfigCipher();
        JSONObject c=read(bot,env);if(StringUtils.isBlank(c.getStr("ownerUserId")))throw BizException.paramError("请先配置接管账号");
        c.set("startedAt",System.currentTimeMillis()).set("generation",UUID.randomUUID().toString());
        c.remove("discovery");c.remove("targetsSeeded");c.remove("sessionAfter");return encrypt(c,env);
    }
    static boolean matches(ImBotEntity bot,ImBotMessageEntity row,Environment env){
        return user(bot)==user(row) && (!user(bot) || Objects.equals(read(bot,env).getStr("generation"),context(row).getStr("generation")));
    }
}
