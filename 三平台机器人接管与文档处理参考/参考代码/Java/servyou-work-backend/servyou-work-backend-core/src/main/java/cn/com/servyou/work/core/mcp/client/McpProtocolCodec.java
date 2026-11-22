package cn.com.servyou.work.core.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shared JSON-RPC and MCP version metadata codec for validation and runtime clients. */
@Component
@RequiredArgsConstructor
public class McpProtocolCodec {

    private static final String PROTOCOL_VERSION_META =
            "io.modelcontextprotocol/protocolVersion";
    private static final String CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo";
    private static final String CLIENT_CAPABILITIES_META =
            "io.modelcontextprotocol/clientCapabilities";

    private final ObjectMapper objectMapper;

    public byte[] request(int id, String method, JsonNode params) throws IOException {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        return objectMapper.writeValueAsBytes(request);
    }

    public byte[] notification(String method) throws IOException {
        return objectMapper.writeValueAsBytes(objectMapper.createObjectNode()
                .put("jsonrpc", "2.0").put("method", method));
    }

    public byte[] result(JsonNode id, JsonNode value) throws IOException {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("result", value);
        return objectMapper.writeValueAsBytes(response);
    }

    public byte[] error(JsonNode id, int code, String message) throws IOException {
        ObjectNode response = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id == null ? objectMapper.nullNode() : id);
        response.set("error", objectMapper.createObjectNode()
                .put("code", code).put("message", message));
        return objectMapper.writeValueAsBytes(response);
    }

    public ObjectNode initializeParams(
            String protocolVersion, String clientName, String clientVersion) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", protocolVersion);
        params.set("capabilities", objectMapper.createObjectNode());
        params.set("clientInfo", clientInfo(clientName, clientVersion));
        return params;
    }

    public ObjectNode modernParams(
            String protocolVersion, String clientName, String clientVersion) {
        ObjectNode params = objectMapper.createObjectNode();
        applyModernMeta(params, protocolVersion, clientName, clientVersion);
        return params;
    }

    public byte[] modernRequest(
            JsonNode source, String protocolVersion, String clientName, String clientVersion)
            throws IOException {
        if (source == null || !source.isObject()) {
            throw new IOException("MCP JSON-RPC request must be an object");
        }
        ObjectNode request = (ObjectNode) source.deepCopy();
        JsonNode existingParams = request.get("params");
        ObjectNode params = existingParams != null && existingParams.isObject()
                ? (ObjectNode) existingParams : objectMapper.createObjectNode();
        applyModernMeta(params, protocolVersion, clientName, clientVersion);
        request.set("params", params);
        return objectMapper.writeValueAsBytes(request);
    }

    /** 复用既有 SSE 响应读取器，或限量读取普通 JSON；达到匹配响应后立即结束。 */
    public byte[] readHttpPayload(java.io.InputStream input,String contentType,byte[] request,long maxBytes) throws IOException {
        JsonNode requestId=parsePayload(request).get("id");
        if(contentType!=null && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream"))
            return McpSseResponseReader.read(input,maxBytes,requestId,objectMapper,event->{ });
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        long copied=cn.hutool.core.io.IoUtil.copy(input,output,cn.hutool.core.io.IoUtil.DEFAULT_BUFFER_SIZE,maxBytes+1,null);
        if(copied>maxBytes)throw new IOException("MCP response exceeds limit");
        return output.toByteArray();
    }

    public JsonNode parsePayload(byte[] body) throws IOException {
        try {
            JsonNode json = objectMapper.readTree(body);
            if (json != null) {
                return json;
            }
        } catch (IOException ignored) {
            // Streamable HTTP can return one JSON-RPC response as an SSE data event.
        }
        String normalized = new String(body, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder data = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            } else if (line.isEmpty() && data.length() > 0) {
                try {
                    return objectMapper.readTree(data.toString());
                } catch (IOException ignored) {
                    data.setLength(0);
                }
            }
        }
        if (data.length() > 0) {
            return objectMapper.readTree(data.toString());
        }
        throw new IOException("MCP response has no JSON-RPC payload");
    }

    public JsonNode requireResult(JsonNode response, int expectedId) {
        if (response == null || !response.isObject()) {
            throw new McpProtocolException("RESPONSE_NOT_OBJECT");
        }
        if (!"2.0".equals(response.path("jsonrpc").asText())) {
            throw new McpProtocolException("JSON_RPC_VERSION_INVALID");
        }
        JsonNode responseId = response.path("id");
        boolean integerId = responseId.canConvertToInt();
        boolean canonicalStringId = responseId.isTextual()
                && Integer.toString(expectedId).equals(responseId.textValue());
        if (!integerId && !canonicalStringId) {
            throw new McpProtocolException("JSON_RPC_ID_NOT_INTEGER");
        }
        if (integerId && responseId.asInt() != expectedId) {
            throw new McpProtocolException("JSON_RPC_ID_MISMATCH");
        }
        if (response.has("error")) {
            throw new McpProtocolException("JSON_RPC_ERROR_RESPONSE");
        }
        if (!response.path("result").isObject()) {
            throw new McpProtocolException("JSON_RPC_RESULT_NOT_OBJECT");
        }
        return response.path("result");
    }

    private void applyModernMeta(
            ObjectNode params, String protocolVersion, String clientName, String clientVersion) {
        JsonNode existingMeta = params.get("_meta");
        ObjectNode meta = existingMeta != null && existingMeta.isObject()
                ? (ObjectNode) existingMeta : params.putObject("_meta");
        meta.put(PROTOCOL_VERSION_META, protocolVersion);
        meta.set(CLIENT_INFO_META, clientInfo(clientName, clientVersion));
        meta.set(CLIENT_CAPABILITIES_META, objectMapper.createObjectNode());
    }

    private ObjectNode clientInfo(String clientName, String clientVersion) {
        return objectMapper.createObjectNode()
                .put("name", clientName)
                .put("version", clientVersion);
    }
}
