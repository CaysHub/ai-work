package cn.com.servyou.work.core.mcp.client;

/** Signals a syntactically valid payload that violates the shared MCP protocol contract. */
public class McpProtocolException extends RuntimeException {

    public McpProtocolException(String message) {
        super(message);
    }
}
