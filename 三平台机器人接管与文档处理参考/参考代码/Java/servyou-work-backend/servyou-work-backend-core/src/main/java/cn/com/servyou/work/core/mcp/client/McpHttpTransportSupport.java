package cn.com.servyou.work.core.mcp.client;

import cn.com.servyou.work.core.mcp.runtime.McpSafeDns;
import okhttp3.OkHttpClient;

/** Common network safety policy for every outbound MCP HTTP transport. */
public final class McpHttpTransportSupport {

    private McpHttpTransportSupport() {
    }

    public static OkHttpClient.Builder secureBuilder(OkHttpClient baseClient) {
        return baseClient.newBuilder()
                .dns(McpSafeDns.allowingPrivateNetworks())
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false);
    }
}
