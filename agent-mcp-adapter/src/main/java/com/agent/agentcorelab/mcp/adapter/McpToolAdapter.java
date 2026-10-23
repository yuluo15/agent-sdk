package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.McpContent;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.ToolSemantics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * Bridges an MCP Tool from a remote or local MCP Server into the SDK's native {@link Tool} contract.
 */
public final class McpToolAdapter implements Tool {

    private final McpClient client;
    private final String mcpToolName;
    private final ToolDefinition definition;
    private final ObjectMapper mapper;

    public McpToolAdapter(McpClient client, McpTool mcpTool) {
        this(client, mcpTool, null, RiskLevel.READ_ONLY, McpJsonRpcCodec.mapper());
    }

    public McpToolAdapter(McpClient client, McpTool mcpTool, String prefix, RiskLevel riskLevel) {
        this(client, mcpTool, prefix, riskLevel, McpJsonRpcCodec.mapper());
    }

    public McpToolAdapter(McpClient client, McpTool mcpTool, String prefix, RiskLevel riskLevel, ObjectMapper mapper) {
        this(client, mcpTool, prefix, riskLevel, null, mapper);
    }

    public McpToolAdapter(McpClient client, McpTool mcpTool, String prefix, RiskLevel riskLevel, ToolSemantics semantics) {
        this(client, mcpTool, prefix, riskLevel, semantics, McpJsonRpcCodec.mapper());
    }

    public McpToolAdapter(McpClient client, McpTool mcpTool, String prefix, RiskLevel riskLevel, ToolSemantics semantics, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        Objects.requireNonNull(mcpTool, "mcpTool cannot be null");
        this.mapper = mapper != null ? mapper : McpJsonRpcCodec.mapper();
        this.mcpToolName = mcpTool.name();

        String sdkToolName = sanitizeToolName(prefix, mcpTool.name());
        String description = (mcpTool.description() != null && !mcpTool.description().isBlank())
                ? mcpTool.description()
                : "MCP Tool: " + mcpTool.name();

        String inputSchemaJson = normalizeInputSchema(mcpTool.inputSchema(), this.mapper);
        RiskLevel resolvedRisk = riskLevel != null ? riskLevel : RiskLevel.READ_ONLY;
        ToolSemantics resolvedSemantics = semantics != null ? semantics : ToolSemantics.defaultFor(resolvedRisk);
        this.definition = new ToolDefinition(
                sdkToolName,
                description,
                inputSchemaJson,
                resolvedRisk,
                resolvedSemantics
        );
    }

    public McpClient client() {
        return client;
    }

    public String mcpToolName() {
        return mcpToolName;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        if (context != null) {
            context.checkActive();
        }

        JsonNode argsNode;
        try {
            if (argumentsJson == null || argumentsJson.isBlank()) {
                argsNode = mapper.createObjectNode();
            } else {
                argsNode = mapper.readTree(argumentsJson);
                if (!argsNode.isObject()) {
                    return new ToolOutcome.Failure("INVALID_ARGUMENT", "Arguments must be a JSON object", false);
                }
            }
        } catch (JsonProcessingException e) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Malformed arguments JSON: " + e.getMessage(), false);
        }

        try {
            McpToolResult toolResult = client.callTool(mcpToolName, argsNode);
            if (toolResult.isError()) {
                String errorText = toolResult.asText();
                if (errorText.isBlank()) {
                    errorText = "MCP Tool reported an error without details";
                }
                return new ToolOutcome.Failure("MCP_TOOL_ERROR", errorText, false);
            }

            String outputJson = formatOutput(toolResult);
            return new ToolOutcome.Success(outputJson);
        } catch (com.agent.agentcorelab.tool.ActiveTimeExceededException | java.util.concurrent.CancellationException ex) {
            throw ex;
        } catch (Exception ex) {
            String errorDetail = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            return new ToolOutcome.Failure(
                    "MCP_EXECUTION_FAILED",
                    "Failed executing MCP tool [" + mcpToolName + "]: " + errorDetail,
                    false
            );
        }
    }

    private String formatOutput(McpToolResult toolResult) throws JsonProcessingException {
        String text = toolResult.asText();

        // 1. If text is already a valid JSON object, preserve it directly
        if (text != null && text.trim().startsWith("{")) {
            try {
                JsonNode parsed = mapper.readTree(text);
                if (parsed.isObject()) {
                    return text;
                }
            } catch (Exception ignored) {
                // Not a valid JSON object, wrap into envelope
            }
        }

        // 2. Wrap into a standard JSON object envelope
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("ok", true);
        envelope.put("result", text != null ? text : "");

        boolean hasRichContent = toolResult.content().stream()
                .anyMatch(c -> !McpContent.TYPE_TEXT.equals(c.type()));
        if (hasRichContent) {
            ArrayNode items = envelope.putArray("content");
            for (McpContent c : toolResult.content()) {
                ObjectNode itemNode = items.addObject();
                itemNode.put("type", c.type());
                if (c.text() != null) itemNode.put("text", c.text());
                if (c.mimeType() != null) itemNode.put("mimeType", c.mimeType());
                if (c.uri() != null) itemNode.put("uri", c.uri());
                if (c.data() != null) itemNode.put("data", c.data());
            }
        }

        return mapper.writeValueAsString(envelope);
    }

    /**
     * Sanitizes raw tool names to strictly adhere to the SDK's `[a-zA-Z0-9_-]{1,64}` naming requirement.
     */
    public static String sanitizeToolName(String prefix, String rawName) {
        String combined = (prefix != null && !prefix.isBlank()) ? prefix + rawName : rawName;
        String sanitized = combined.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "mcp_tool";
        }
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized;
    }

    private static String normalizeInputSchema(JsonNode schemaNode, ObjectMapper mapper) {
        ObjectNode schemaObj;
        if (schemaNode != null && schemaNode.isObject()) {
            schemaObj = (ObjectNode) schemaNode.deepCopy();
        } else {
            schemaObj = mapper.createObjectNode();
        }

        if (!schemaObj.has("type") || !"object".equals(schemaObj.get("type").asText())) {
            schemaObj.put("type", "object");
        }
        if (!schemaObj.has("properties")) {
            schemaObj.putObject("properties");
        }

        try {
            return mapper.writeValueAsString(schemaObj);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
