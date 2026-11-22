package cn.com.servyou.work.core.mcp.client;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Builds the canonical protocol profile digest used at validation and runtime. */
public final class McpProtocolProfileFactory {

    private final ObjectMapper objectMapper;

    public McpProtocolProfileFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public McpProtocolProfileSnapshot create(
            String protocolVersion, String protocolEra, String transport,
            JsonNode serverInfo, JsonNode capabilities) throws IOException {
        JsonNode safeServerInfo = serverInfo == null
                ? objectMapper.createObjectNode() : serverInfo;
        String serverName = bounded(
                StringUtils.trimToNull(safeServerInfo.path("name").asText()), 128);
        String serverVersion = bounded(
                StringUtils.trimToNull(safeServerInfo.path("version").asText()), 64);
        JsonNode safeCapabilities = capabilities != null && capabilities.isObject()
                ? canonicalize(capabilities) : objectMapper.createObjectNode();
        String capabilitiesJson = objectMapper.writeValueAsString(safeCapabilities);
        ObjectNode digestSource = objectMapper.createObjectNode();
        digestSource.put("protocolVersion", protocolVersion);
        digestSource.put("protocolEra", protocolEra);
        digestSource.put("transport", transport);
        digestSource.put("serverName", serverName);
        digestSource.put("serverVersion", serverVersion);
        digestSource.set("capabilities", safeCapabilities);
        return McpProtocolProfileSnapshot.builder()
                .protocolVersion(protocolVersion)
                .protocolEra(protocolEra)
                .transport(transport)
                .serverName(serverName)
                .serverVersion(serverVersion)
                .capabilitiesJson(capabilitiesJson)
                .profileDigest(DigestUtil.sha256Hex(
                        objectMapper.writeValueAsString(digestSource)))
                .build();
    }

    /** Tools compatibility depends on protocol and transport; advertised metadata is informational. */
    public boolean matchesPublished(String protocolVersion, String protocolEra, String transport,
                                    McpProtocolProfileSnapshot actual) {
        return actual != null
                && StringUtils.isNotBlank(protocolVersion)
                && StringUtils.isNotBlank(protocolEra)
                && StringUtils.isNotBlank(transport)
                && protocolVersion.equals(actual.getProtocolVersion())
                && protocolEra.equals(actual.getProtocolEra())
                && transport.equals(actual.getTransport());
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((name, value) -> result.set(name, canonicalize(value)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private String bounded(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
