package cn.com.servyou.work.core.mcp.client;

import cn.com.servyou.work.core.mcp.McpProtocolVersions;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Shared stateful Legacy MCP negotiation and session adapter. */
public final class McpLegacySessionAdapter {

    private static final int INITIALIZE_REQUEST_ID = 1;

    private final McpProtocolCodec protocolCodec;

    public McpLegacySessionAdapter(McpProtocolCodec protocolCodec) {
        this.protocolCodec = protocolCodec;
    }

    public NegotiationPlan validationPlan(String configuredVersion, List<String> enabledVersions) {
        String configured = StringUtils.defaultIfBlank(
                configuredVersion, McpProtocolVersions.V_2025_03_26);
        List<String> enabled = enabledVersions == null
                ? Collections.emptyList() : enabledVersions;
        if (!McpProtocolVersions.AUTO.equals(configured)) {
            if (!isEnabledLegacy(configured, enabled)) {
                throw new Failure(Reason.UNSUPPORTED_VERSION);
            }
            return new NegotiationPlan(configured, false, false, enabled);
        }
        for (String candidate : enabled) {
            if (McpProtocolVersions.isLegacy(candidate)) {
                return new NegotiationPlan(candidate, true, false, enabled);
            }
        }
        throw new Failure(Reason.NO_COMMON_VERSION);
    }

    public NegotiationPlan pinnedPlan(String protocolVersion) {
        if (!McpProtocolVersions.isLegacy(protocolVersion)) {
            throw new Failure(Reason.UNSUPPORTED_VERSION);
        }
        return new NegotiationPlan(protocolVersion, false, true,
                McpProtocolVersions.legacy());
    }

    public Session open(NegotiationPlan plan, String clientName, String clientVersion,
                        ExchangeTransport transport) throws IOException {
        Exchange initialized = transport.exchange(
                protocolCodec.request(INITIALIZE_REQUEST_ID, "initialize",
                        protocolCodec.initializeParams(
                                plan.requestedVersion, clientName, clientVersion)),
                plan.requestedVersion, null, true, "INITIALIZE_REQUEST");
        JsonNode result;
        try {
            result = protocolCodec.requireResult(
                    protocolCodec.parsePayload(initialized.body), INITIALIZE_REQUEST_ID);
        } catch (McpProtocolException exception) {
            throw new Failure(Reason.INVALID_RESPONSE, initialized.sessionId,
                    plan.requestedVersion, exception);
        }
        String negotiatedVersion = StringUtils.trimToNull(
                result.path("protocolVersion").asText(null));
        requireNegotiatedVersion(plan, negotiatedVersion, initialized.sessionId);

        Exchange notification = transport.exchange(
                protocolCodec.notification("notifications/initialized"),
                negotiatedVersion, initialized.sessionId, false,
                "INITIALIZED_NOTIFICATION");
        String sessionId = requireSameSession(
                initialized.sessionId, notification.sessionId, negotiatedVersion);
        return new Session(protocolCodec, transport, plan.requestedVersion,
                negotiatedVersion, sessionId, result.path("serverInfo").deepCopy(),
                result.path("capabilities").deepCopy());
    }

    private void requireNegotiatedVersion(
            NegotiationPlan plan, String negotiated, String sessionId) {
        if (!isEnabledLegacy(negotiated, plan.enabledVersions)) {
            throw new Failure(Reason.UNSUPPORTED_VERSION, sessionId, negotiated, null);
        }
        if (plan.auto || plan.requestedVersion.equals(negotiated)) {
            return;
        }
        if (plan.pinned && McpProtocolVersions.isDowngrade(
                plan.requestedVersion, negotiated)) {
            throw new Failure(Reason.DOWNGRADE_DETECTED, sessionId, negotiated, null);
        }
        throw new Failure(plan.pinned ? Reason.PROFILE_CHANGED : Reason.INVALID_RESPONSE,
                sessionId, negotiated, null);
    }

    private static boolean isEnabledLegacy(String version, List<String> enabledVersions) {
        return McpProtocolVersions.isLegacy(version) && enabledVersions.contains(version);
    }

    private static String requireSameSession(
            String current, String returned, String protocolVersion) {
        if (StringUtils.isNotBlank(current) && StringUtils.isNotBlank(returned)
                && !current.equals(returned)) {
            throw new Failure(Reason.SESSION_CHANGED, current, protocolVersion, null);
        }
        return StringUtils.defaultIfBlank(current, returned);
    }

    @FunctionalInterface
    public interface ExchangeTransport {
        Exchange exchange(byte[] payload, String protocolVersion, String sessionId,
                          boolean requireBody, String stage) throws IOException;
    }

    @Getter
    public static final class Exchange {
        private final byte[] body;
        private final String sessionId;

        public Exchange(byte[] body, String sessionId) {
            this.body = body == null ? new byte[0] : body;
            this.sessionId = sessionId;
        }
    }

    @Getter
    public static final class Session {
        private final McpProtocolCodec protocolCodec;
        private final ExchangeTransport transport;
        private final String requestedProtocolVersion;
        private final String protocolVersion;
        private String sessionId;
        private final JsonNode serverInfo;
        private final JsonNode capabilities;

        private Session(McpProtocolCodec protocolCodec, ExchangeTransport transport,
                        String requestedProtocolVersion, String protocolVersion,
                        String sessionId, JsonNode serverInfo, JsonNode capabilities) {
            this.protocolCodec = protocolCodec;
            this.transport = transport;
            this.requestedProtocolVersion = requestedProtocolVersion;
            this.protocolVersion = protocolVersion;
            this.sessionId = sessionId;
            this.serverInfo = serverInfo;
            this.capabilities = capabilities;
        }

        public Exchange request(int requestId, String method, JsonNode params, String stage)
                throws IOException {
            Exchange response = transport.exchange(
                    protocolCodec.request(requestId, method, params),
                    protocolVersion, sessionId, true, stage);
            sessionId = requireSameSession(sessionId, response.sessionId, protocolVersion);
            return response;
        }
    }

    @Getter
    public static final class NegotiationPlan {
        private final String requestedVersion;
        private final boolean auto;
        private final boolean pinned;
        private final List<String> enabledVersions;

        private NegotiationPlan(String requestedVersion, boolean auto, boolean pinned,
                                List<String> enabledVersions) {
            this.requestedVersion = requestedVersion;
            this.auto = auto;
            this.pinned = pinned;
            this.enabledVersions = enabledVersions;
        }
    }

    public enum Reason {
        NO_COMMON_VERSION,
        UNSUPPORTED_VERSION,
        INVALID_RESPONSE,
        SESSION_CHANGED,
        PROFILE_CHANGED,
        DOWNGRADE_DETECTED
    }

    @Getter
    public static final class Failure extends RuntimeException {
        private final Reason reason;
        private final String sessionId;
        private final String protocolVersion;

        private Failure(Reason reason) {
            this(reason, null, null, null);
        }

        private Failure(Reason reason, String sessionId) {
            this(reason, sessionId, null, null);
        }

        private Failure(Reason reason, String sessionId, Throwable cause) {
            this(reason, sessionId, null, cause);
        }

        private Failure(Reason reason, String sessionId,
                        String protocolVersion, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
            this.sessionId = sessionId;
            this.protocolVersion = protocolVersion;
        }
    }
}
