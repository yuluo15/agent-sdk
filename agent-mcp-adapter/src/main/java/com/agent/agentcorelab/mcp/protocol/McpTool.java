package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard MCP Tool definition received from an MCP Server via `tools/list`.
 */
public record McpTool(
        String name,
        String description,
        JsonNode inputSchema
) {
    public McpTool {
        Objects.requireNonNull(name, "tool name cannot be null");
        description = description != null ? description : "";
    }
}
