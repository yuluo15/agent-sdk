package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result returned by the MCP server following an `initialize` request.
 */
public record McpInitializeResult(
        String protocolVersion,
        JsonNode capabilities,
        ServerInfo serverInfo
) {
    public record ServerInfo(String name, String version) {}

    public String serverName() {
        return serverInfo != null ? serverInfo.name() : "unknown";
    }

    public String serverVersion() {
        return serverInfo != null ? serverInfo.version() : "unknown";
    }
}
