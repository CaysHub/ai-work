package cn.com.servyou.work.core.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;

/** Reads a POST SSE exchange until its JSON-RPC response, without waiting for stream EOF. */
public final class McpSseResponseReader {

    private McpSseResponseReader() {
    }

    public static byte[] read(InputStream input, long maxBytes, JsonNode requestId,
                              ObjectMapper mapper, EventConsumer consumer) throws IOException {
        PushbackInputStream stream = new PushbackInputStream(input, 1);
        ByteArrayOutputStream event = new ByteArrayOutputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        boolean firstLine = true;
        boolean skipLf = false;
        long total = 0;
        int next;
        while ((next = stream.read()) != -1) {
            if (++total > maxBytes) {
                throw new IOException("MCP SSE response exceeded the configured limit");
            }
            event.write(next);
            if (skipLf && next == '\n') {
                skipLf = false;
                continue;
            }
            skipLf = false;
            if (next != '\r' && next != '\n') {
                line.write(next);
                continue;
            }
            if (next == '\r') {
                // CR already ends the line. Consume an available LF without waiting for more data.
                skipLf = true;
                if (stream.available() > 0) {
                    int following = stream.read();
                    if (following == '\n') {
                        if (++total > maxBytes) {
                            throw new IOException("MCP SSE response exceeded the configured limit");
                        }
                        event.write(following);
                        skipLf = false;
                    } else if (following != -1) {
                        stream.unread(following);
                        skipLf = false;
                    }
                }
            }
            String value = new String(line.toByteArray(), StandardCharsets.UTF_8);
            line.reset();
            if (firstLine && value.startsWith("\uFEFF")) {
                value = value.substring(1);
            }
            firstLine = false;
            if (!value.isEmpty()) {
                if (value.startsWith("data:")) {
                    if (hasData) {
                        data.append('\n');
                    }
                    String field = value.substring(5);
                    data.append(field.startsWith(" ") ? field.substring(1) : field);
                    hasData = true;
                }
                continue;
            }
            consumer.accept(event.toByteArray());
            event.reset();
            if (data.length() > 0) {
                byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
                JsonNode message = mapper.readTree(payload);
                JsonNode responseId = message == null ? null : message.get("id");
                // Existing MCP services may echo numeric IDs as strings (or vice versa).
                boolean sameId = requestId != null && responseId != null
                        && (requestId.isNumber() || requestId.isTextual())
                        && (responseId.isNumber() || responseId.isTextual())
                        && requestId.asText().equals(responseId.asText());
                if (sameId && "2.0".equals(message.path("jsonrpc").asText())
                        && (message.has("result") || message.has("error"))) {
                    return payload;
                }
            }
            data.setLength(0);
            hasData = false;
        }
        throw new IOException("MCP SSE response ended without a matching JSON-RPC response");
    }

    @FunctionalInterface
    public interface EventConsumer {
        void accept(byte[] event) throws IOException;
    }
}
