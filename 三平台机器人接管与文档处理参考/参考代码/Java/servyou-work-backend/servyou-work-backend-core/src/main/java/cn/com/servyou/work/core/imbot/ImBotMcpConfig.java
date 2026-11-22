package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotMcpCapability;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.AesCryptoUtil;
import cn.hutool.json.JSONUtil;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import java.util.*;

/** 七类授权合并保存在已有加密字段，兼容原来只保存文档URL的配置。 */
final class ImBotMcpConfig {
    /** 原机器人采用真人模式时，同一加密字段中的应用公共授权。 */
    private static final String APPLICATION_URLS = "applicationMcpUrls";

    private ImBotMcpConfig() { }

    /** 解密已保存的企微授权配置，由调用方校验读取权限。 */
    static Map<String,String> read(String cipher,Environment environment) {
        Map<String,String> values=new LinkedHashMap<>();
        if(StringUtils.isBlank(cipher))return values;
        String plain=AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment),cipher);
        if(plain.startsWith("{")) {
            return endpoints(JSONUtil.parseObj(plain));
        } else values.put(ImBotMcpCapability.DOCUMENT.getCode(),plain);
        return values;
    }

    private static Map<String, String> endpoints(cn.hutool.json.JSONObject config) {
        Map<String, String> values = new LinkedHashMap<>();
        if (config == null) return values;
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (APPLICATION_URLS.equals(entry.getKey())) continue;
            ImBotMcpCapability.fromCode(entry.getKey());
            if (!(entry.getValue() instanceof String)) throw BizException.paramError("企微授权配置格式错误");
            values.put(entry.getKey(), (String) entry.getValue());
        }
        return values;
    }

    private static cn.hutool.json.JSONObject applicationConfig(String cipher, Environment environment) {
        if (StringUtils.isBlank(cipher)) return null;
        String plain = AesCryptoUtil.decrypt(AesCryptoUtil.resolveKey(environment), cipher);
        return plain.startsWith("{") ? JSONUtil.parseObj(plain).getJSONObject(APPLICATION_URLS) : null;
    }

    static Map<String, String> readApplication(String cipher, boolean personal, Environment environment) {
        cn.hutool.json.JSONObject application = applicationConfig(cipher, environment);
        if (application != null || personal) return endpoints(application);
        return read(cipher, environment);
    }

    static String mergeApplication(String cipher, boolean personal, String documentUrl,
            Map<String, String> updates, Environment environment) {
        if (documentUrl == null && (updates == null || updates.isEmpty())) return cipher;
        if (!personal && applicationConfig(cipher, environment) == null) return merge(cipher, documentUrl, updates, environment);
        Map<String, String> values = readApplication(cipher, personal, environment);
        mergeValues(values, documentUrl, updates);
        return encrypt(read(cipher, environment), JSONUtil.parseObj(values), environment);
    }

    /** 身份模式切换时标记旧配置的授权范围，应用与个人授权各自保留。 */
    static String separateApplication(String cipher, boolean personal, Environment environment) {
        if (StringUtils.isBlank(cipher) || applicationConfig(cipher, environment) != null) return cipher;
        Map<String, String> existing = read(cipher, environment);
        return personal ? encrypt(existing, JSONUtil.createObj(), environment)
                : encrypt(Collections.emptyMap(), JSONUtil.parseObj(existing), environment);
    }

    /** 保存旧版企微真人配置时只复制其本人授权。 */
    static String copyPersonal(String cipher, Environment environment) {
        return encrypt(read(cipher, environment), null, environment);
    }

    /** 类别及官方路径必须匹配，防止将一个能力的凭据发送到其他入口。 */
    static void requireEndpoint(ImBotMcpCapability capability,String endpoint) {
        ImBotWecomDocuments.requireEndpoint(endpoint);
        HttpUrl url=HttpUrl.get(endpoint);
        if(!"qyapi.weixin.qq.com".equals(url.host()) || !url.encodedPath().equals("/mcp/v2/bot/"+capability.getCode())
                || url.querySize()!=1 || StringUtils.isBlank(url.queryParameter("apikey")))
            throw BizException.paramError("企微能力类别须对应官方v2授权地址");
    }

    /** 修改时按类别合并，不覆盖未提交类别；沿用原版本CAS统一落库。 */
    static String merge(String cipher,String documentUrl,Map<String,String> updates,Environment environment) {
        if(documentUrl==null && updates==null)return cipher;
        Map<String,String> values=read(cipher,environment);
        mergeValues(values, documentUrl, updates);
        return encrypt(values, applicationConfig(cipher, environment), environment);
    }

    private static void mergeValues(Map<String, String> values, String documentUrl, Map<String, String> updates) {
        Map<String,String> changes=new LinkedHashMap<>();
        if(updates!=null)changes.putAll(updates);
        String doc=ImBotMcpCapability.DOCUMENT.getCode();
        if(documentUrl!=null) {
            if(changes.containsKey(doc) && !Objects.equals(changes.get(doc),documentUrl))throw BizException.paramError("文档授权地址重复且不一致");
            changes.put(doc,documentUrl);
        }
        for(Map.Entry<String,String> entry:changes.entrySet()) {
            ImBotMcpCapability capability=ImBotMcpCapability.fromCode(entry.getKey());
            if(entry.getValue()==null)throw BizException.paramError("清除授权请传空字符串，不要传null");
            if(entry.getValue().isEmpty())values.remove(entry.getKey());
            else {
                // 原文档字段允许既有官方地址；新批量字段只接受对应v2入口。
                 if(updates!=null && updates.containsKey(entry.getKey()))requireEndpoint(capability,entry.getValue());
                else ImBotWecomDocuments.requireEndpoint(entry.getValue());
                values.put(entry.getKey(),entry.getValue());
            }
        }
    }

    private static String encrypt(Map<String, String> values, cn.hutool.json.JSONObject application, Environment environment) {
        cn.hutool.json.JSONObject result = JSONUtil.parseObj(values);
        if (application != null) result.set(APPLICATION_URLS, application);
        return result.isEmpty() ? null : AesCryptoUtil.encrypt(AesCryptoUtil.resolveKey(environment), result.toString());
    }
}
