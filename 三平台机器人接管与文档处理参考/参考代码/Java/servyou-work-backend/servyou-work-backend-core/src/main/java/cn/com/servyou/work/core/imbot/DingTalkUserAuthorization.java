package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.api.enums.ImBotEnums.Error;
import cn.com.servyou.work.api.exception.BizException;
import cn.com.servyou.work.core.utils.OkHttpClientUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 通过钉钉官方 Workspace 托管应用完成本人设备授权。 */
@Slf4j
final class DingTalkUserAuthorization {
    /** 托管应用标识、令牌及企业权限接口。 */
    private static final String WORKSPACE = "https://mcp.dingtalk.com";
    /** 设备授权及授权码轮询入口。 */
    private static final String LOGIN = "https://login.dingtalk.com";
    /** 钉钉设备授权的标准 OAuth 授权类型。 */
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";

    private DingTalkUserAuthorization() {
    }

    /** 获取一次授权链接，设备码及应用标识仅保存到后端加密配置。 */
    static String start(JSONObject config) throws IOException {
        JSONObject client = response("clientId", OkHttpClientUtil.jsonNoRetry(
                "GET", WORKSPACE + "/cli/clientId", Collections.emptyMap(), null));
        String clientId = client.getStr("result");
        if (StringUtils.isBlank(clientId)) {
            throw failure("钉钉 Workspace 未返回 OAuth 应用标识");
        }
        JSONObject grant = response("deviceCode", OkHttpClientUtil.postFormNoRetry(
                LOGIN + "/oauth2/device/code.json", new FormBody.Builder()
                        .add("client_id", clientId).add("scope", "openid corpid").build())).getJSONObject("result");
        if (grant == null || StringUtils.isBlank(grant.getStr("deviceCode")) || grant.getLong("expiresIn", 0L) <= 0) {
            throw failure("钉钉未返回有效的设备授权码");
        }
        HttpUrl url = HttpUrl.parse(StringUtils.defaultString(grant.getStr("verificationUriComplete")));
        if (url == null || !url.isHttps() || !"login.dingtalk.com".equals(url.host()) || url.port() != 443
                || !url.username().isEmpty() || !url.password().isEmpty()) {
            throw failure("钉钉返回的设备授权地址无效");
        }
        config.set("dingTalkOAuthClientId", clientId)
                .set("dingTalkDeviceCode", grant.getStr("deviceCode"))
                .set("dingTalkFlowId", grant.getStr("flowId"))
                .set("oauthExpiresAt", System.currentTimeMillis() + Math.min(900L, grant.getLong("expiresIn")) * 1000L);
        return url.toString();
    }

    /** 完成接口每次只查询一次设备授权状态，等待用户确认时保留当前配置版本。 */
    static String authorizationCode(JSONObject config) throws IOException {
        String deviceCode = config.getStr("dingTalkDeviceCode");
        String clientId = config.getStr("dingTalkOAuthClientId");
        if (StringUtils.isAnyBlank(deviceCode, clientId) || config.getLong("oauthExpiresAt", 0L) <= System.currentTimeMillis()) {
            throw failure("钉钉设备授权已失效，请重新开始授权");
        }
        String flowId = config.getStr("dingTalkFlowId");
        JSONObject result;
        if (StringUtils.isNotBlank(flowId)) {
            String url = HttpUrl.get(WORKSPACE + "/cli/oauth/device/poll").newBuilder()
                    .addQueryParameter("flowId", flowId).build().toString();
            result = response("deviceStatus", OkHttpClientUtil.jsonNoRetry("GET", url, Collections.emptyMap(), null));
            result = result.getJSONObject("data") == null ? result.getJSONObject("result") : result.getJSONObject("data");
        } else {
            result = response("deviceStatus", OkHttpClientUtil.postFormNoRetry(
                    LOGIN + "/oauth2/device/token.json", new FormBody.Builder()
                            .add("grant_type", DEVICE_GRANT).add("device_code", deviceCode)
                            .add("client_id", clientId).build())).getJSONObject("result");
        }
        return requireAuthorizationCode(result);
    }

    static String requireAuthorizationCode(JSONObject result) {
        if (result == null) {
            throw failure("钉钉未返回设备授权状态");
        }
        String status = result.getStr("status", "");
        String error = result.getStr("error", "");
        if ("REJECTED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)
                || "access_denied".equals(error) || "expired_token".equals(error)) {
            throw failure("钉钉设备授权已拒绝或过期，请重新开始授权");
        }
        String code = result.getStr("authCode");
        if (StringUtils.isBlank(code)) {
            throw failure("请先在钉钉授权页确认，再返回完成授权");
        }
        return code;
    }

    /** 交换本人令牌并核对当前企业是否许可该接入渠道。 */
    static JSONObject exchange(String clientId, String code) throws IOException {
        JSONObject request = JSONUtil.createObj().set("clientId", clientId).set("authCode", code)
                .set("grantType", "authorization_code");
        JSONObject data = token("getToken", request);
        Map<String, String> headers = Collections.singletonMap("x-user-access-token", data.getStr("accessToken"));
        JSONObject permission = response("cliAuthEnabled", OkHttpClientUtil.jsonNoRetry(
                "GET", WORKSPACE + "/cli/cliAuthEnabled", headers, null)).getJSONObject("result");
        if (permission == null || !Boolean.TRUE.equals(permission.getBool("cliAuthEnabled"))) {
            log.warn("钉钉 Workspace 企业渠道未授权 corpId={}", data.getStr("corpId"));
            throw new BizException(Error.TOOL_PERMISSION_DENIED.getCode(),
                    "钉钉企业未向本人开放当前接入渠道，请在开发者平台的 CLI 设置中核对 Agent 管理");
        }
        return data;
    }

    /** 严格核对组织员工账号，并取得消息接口使用的开放钉钉号。 */
    static String verifyOwner(JSONObject token, String expectedUser) throws IOException {
        JSONObject profile = DingTalkUserMessages.call(DingTalkUserMessages.Tool.CURRENT_USER,
                token.getStr("accessToken"), JSONUtil.createObj());
        JSONObject employee = owner(profile, token.getStr("corpId"), expectedUser);
        if (employee == null || StringUtils.isBlank(employee.getStr("openDingTalkId"))) {
            log.warn("钉钉 Workspace 本人绑定校验失败 corpId={}, boundExternalUserId={}", token.getStr("corpId"), expectedUser);
            throw BizException.noPermission("钉钉授权人与当前接管绑定不一致，请核对钉钉组织与用户");
        }
        return employee.getStr("openDingTalkId");
    }

    static boolean matchesOwner(JSONObject profile, String corpId, String expectedUser) {
        return owner(profile, corpId, expectedUser) != null;
    }

    private static JSONObject owner(JSONObject profile, String corpId, String expectedUser) {
        if (profile == null || StringUtils.isAnyBlank(corpId, expectedUser) || Boolean.FALSE.equals(profile.getBool("success"))) {
            return null;
        }
        JSONArray records = profile.getJSONArray("result");
        if (records == null) {
            return null;
        }
        JSONObject matched = null;
        for (int i = 0; i < records.size(); i++) {
            JSONObject employee = records.getJSONObject(i).getJSONObject("orgEmployeeModel");
            if (employee == null || !corpId.equals(employee.getStr("corpId"))) {
                continue;
            }
            String user = employee.getStr("userId");
            String alternate = employee.getStr("userid");
            if (matched != null || (StringUtils.isNotBlank(user) && StringUtils.isNotBlank(alternate) && !user.equals(alternate))
                    || !expectedUser.equals(StringUtils.defaultIfBlank(user, alternate))) {
                return null;
            }
            matched = employee;
        }
        return matched;
    }

    /** 复用真人接管持有者的原子配置写入，仅在批处理统一刷新令牌。 */
    static void refresh(JSONObject config) throws IOException {
        JSONObject request = JSONUtil.createObj().set("clientId", config.getStr("dingTalkOAuthClientId"))
                .set("refreshToken", config.getStr("refreshToken")).set("grantType", "refresh_token");
        JSONObject refreshed = token("refreshToken", request);
        if (!refreshed.getStr("corpId").equals(config.getStr("dingTalkCorpId"))) {
            throw failure("钉钉用户续期返回了不同的组织，请重新授权");
        }
        saveToken(config, refreshed);
    }

    static boolean ready(JSONObject config) {
        return !config.getBool("refreshing", false) && !StringUtils.isAnyBlank(config.getStr("refreshToken"),
                config.getStr("dingTalkOAuthClientId"), config.getStr("dingTalkCorpId"), config.getStr("messageUserId"));
    }

    static void saveToken(JSONObject config, JSONObject data) {
        config.set("accessToken", data.getStr("accessToken")).set("refreshToken", data.getStr("refreshToken"))
                .set("dingTalkCorpId", data.getStr("corpId"))
                .set("accessExpiresAt", System.currentTimeMillis() + data.getLong("expiresIn") * 1000L);
        config.remove("refreshing");
    }

    private static JSONObject token(String action, JSONObject request) throws IOException {
        JSONObject envelope = response(action, OkHttpClientUtil.jsonNoRetry(
                "POST", WORKSPACE + "/oauth2/" + action, Collections.emptyMap(), request.toString()));
        JSONObject data = envelope.getJSONObject("result");
        if (data == null) {
            data = envelope;
        }
        if (StringUtils.isAnyBlank(data.getStr("accessToken"), data.getStr("refreshToken"), data.getStr("corpId"))
                || data.getLong("expiresIn", 0L) <= 0 || data.getLong("expiresIn") > 365L * 86400) {
            throw failure("钉钉未返回有效的组织用户令牌和续期信息");
        }
        return data;
    }

    private static JSONObject response(String action, OkHttpClientUtil.HttpResult http) {
        JSONObject data = JSONUtil.isTypeJSONObject(http.getBody()) ? JSONUtil.parseObj(http.getBody()) : new JSONObject();
        boolean terminalDeviceState = "deviceStatus".equals(action) && data.getJSONObject("data") != null
                && StringUtils.isNotBlank(data.getJSONObject("data").getStr("status"));
        if (!http.isSuccessful() || data.isEmpty() || (Boolean.FALSE.equals(data.getBool("success")) && !terminalDeviceState)) {
            String code = data.getStr("errorCode", data.getStr("code", ""));
            log.warn("钉钉 Workspace 授权接口失败 action={}, httpStatus={}, providerCode={}",
                    action, http.getStatusCode(), code.matches("[a-zA-Z0-9_.-]{1,80}") ? code : "");
            throw failure("钉钉授权请求失败，请核对企业授权及后台日志");
        }
        return data;
    }

    private static BizException failure(String message) {
        return new BizException(Error.PLATFORM_AUTH_FAILED.getCode(), message);
    }
}
