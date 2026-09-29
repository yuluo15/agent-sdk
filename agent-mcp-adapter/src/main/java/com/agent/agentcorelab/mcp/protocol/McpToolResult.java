package com.agent.agentcorelab.mcp.protocol;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Result payload returned from an MCP `tools/call` invocation.
 */
public record McpToolResult(
        List<McpContent> content,
        boolean isError
) {
    public McpToolResult {
        content = content != null ? List.copyOf(content) : List.of();
    }

    public static McpToolResult success(String text) {
        return new McpToolResult(List.of(McpContent.text(text)), false);
    }

    public static McpToolResult failure(String errorMessage) {
        return new McpToolResult(List.of(McpContent.text(errorMessage)), true);
    }

    /**
     * Extracts and concatenates all text fragments into a single string.
     */
    public String asText() {
        if (content.isEmpty()) {
            return "";
        }
        return content.stream()
                .filter(c -> McpContent.TYPE_TEXT.equals(c.type()) && c.text() != null)
                .map(McpContent::text)
                .collect(Collectors.joining("\n"));
    }
}
