package cn.com.servyou.work.core.mcp.client;

import lombok.Builder;
import lombok.Value;

/** Canonical, credential-free MCP protocol identity shared by validation and runtime. */
@Value
@Builder
public class McpProtocolProfileSnapshot {

    String protocolVersion;
    String protocolEra;
    String transport;
    String serverName;
    String serverVersion;
    String capabilitiesJson;
    String profileDigest;
}
