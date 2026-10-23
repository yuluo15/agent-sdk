package com.agent.agentcorelab.mcp.protocol;

import com.agent.agentcorelab.tool.ToolJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * High-performance, thread-safe JSON-RPC 2.0 message parser and serializer for MCP.
 */
public final class McpJsonRpcCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJsonRpcCodec() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode parseJson(String json) {
        return ToolJson.parse(json);
    }

    public static String encode(JsonRpcMessage message) {
        Objects.requireNonNull(message, "message cannot be null");
        return ToolJson.write(message);
    }

    public static String encodeMessage(JsonRpcMessage message) {
        return encode(message);
    }

    public static JsonRpcMessage decode(String json) {
        Objects.requireNonNull(json, "json cannot be null");
        JsonNode node = ToolJson.parse(json);
        if (!node.isObject()) {
            throw new IllegalArgumentException("JSON-RPC message must be a JSON object, got: " + json);
        }

        String jsonrpc = node.path("jsonrpc").asText(McpProtocolConstants.JSONRPC_VERSION);
        JsonNode idNode = node.get("id");
        String id = idNode != null && !idNode.isNull() ? idNode.asText() : null;
        JsonNode methodNode = node.get("method");

        if (methodNode != null && !methodNode.isNull()) {
            String method = methodNode.asText();
            JsonNode params = node.get("params");
            if (id != null) {
                return new JsonRpcRequest(jsonrpc, id, method, params);
            } else {
                return new JsonRpcNotification(jsonrpc, method, params);
            }
        }

        // Response frame
        if (id == null) {
            throw new IllegalArgumentException("JSON-RPC response must contain an 'id': " + json);
        }

        JsonNode errorNode = node.get("error");
        if (errorNode != null && !errorNode.isNull()) {
            int code = errorNode.path("code").asInt(McpProtocolConstants.INTERNAL_ERROR);
            String messageStr = errorNode.path("message").asText("Internal Error");
            JsonNode data = errorNode.get("data");
            return new JsonRpcResponse(jsonrpc, id, null, new JsonRpcError(code, messageStr, data));
        }

        JsonNode result = node.get("result");
        return new JsonRpcResponse(jsonrpc, id, result, null);
    }

    public static JsonRpcMessage decodeMessage(String json) {
        return decode(json);
    }

    public static List<McpTool> decodeToolsList(JsonNode resultNode) {
        if (resultNode == null || !resultNode.isObject()) {
            return List.of();
        }
        JsonNode toolsArray = resultNode.get("tools");
        if (toolsArray == null || !toolsArray.isArray()) {
            return List.of();
        }

        List<McpTool> tools = new ArrayList<>(toolsArray.size());
        for (JsonNode toolNode : toolsArray) {
            String name = toolNode.path("name").asText();
            String description = toolNode.path("description").asText("");
            JsonNode inputSchema = toolNode.path("inputSchema");
            if (!name.isBlank()) {
                tools.add(new McpTool(name, description, inputSchema));
            }
        }
        return List.copyOf(tools);
    }

    public static McpToolResult decodeToolResult(JsonNode resultNode) {
        if (resultNode == null) {
            return McpToolResult.success("");
        }
        boolean isError = resultNode.path("isError").asBoolean(false);
        JsonNode contentArray = resultNode.get("content");
        if (contentArray == null || !contentArray.isArray()) {
            return new McpToolResult(List.of(McpContent.text(resultNode.toString())), isError);
        }

        List<McpContent> contents = new ArrayList<>(contentArray.size());
        for (JsonNode cNode : contentArray) {
            String type = cNode.path("type").asText(McpContent.TYPE_TEXT);
            String text = cNode.path("text").asText(null);
            String data = cNode.path("data").asText(null);
            String mimeType = cNode.path("mimeType").asText(null);
            String uri = cNode.path("uri").asText(null);
            contents.add(new McpContent(type, text, data, mimeType, uri));
        }
        return new McpToolResult(contents, isError);
    }

    public static McpInitializeResult decodeInitializeResult(JsonNode resultNode) {
        if (resultNode == null || !resultNode.isObject()) {
            throw new IllegalArgumentException("Expected object for initialize result: " + resultNode);
        }
        String protocolVersion = resultNode.path("protocolVersion").asText(McpProtocolConstants.LATEST_PROTOCOL_VERSION);
        JsonNode capabilities = resultNode.get("capabilities");
        JsonNode serverInfoNode = resultNode.get("serverInfo");
        String serverName = serverInfoNode != null ? serverInfoNode.path("name").asText("unknown") : "unknown";
        String serverVer = serverInfoNode != null ? serverInfoNode.path("version").asText("1.0.0") : "1.0.0";
        return new McpInitializeResult(protocolVersion, capabilities, new McpInitializeResult.ServerInfo(serverName, serverVer));
    }

    public static JsonNode createInitializeParams(String clientName, String clientVersion) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("protocolVersion", McpProtocolConstants.LATEST_PROTOCOL_VERSION);

        ObjectNode capabilities = root.putObject("capabilities");
        ObjectNode toolsCap = capabilities.putObject("tools");
        toolsCap.put("listChanged", true);

        ObjectNode clientInfo = root.putObject("clientInfo");
        clientInfo.put("name", clientName != null ? clientName : "agent-sdk");
        clientInfo.put("version", clientVersion != null ? clientVersion : "1.1.0");

        return root;
    }

    public static JsonNode createToolCallParams(String toolName, JsonNode arguments) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", toolName);
        if (arguments != null && !arguments.isNull()) {
            root.set("arguments", arguments);
        } else {
            root.putObject("arguments");
        }
        return root;
    }
}
