package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard JSON-RPC 2.0 notification message frame (no id, fire-and-forget).
 */
public record JsonRpcNotification(
        String jsonrpc,
        String method,
        JsonNode params
) implements JsonRpcMessage {

    public JsonRpcNotification {
        jsonrpc = jsonrpc != null ? jsonrpc : McpProtocolConstants.JSONRPC_VERSION;
        Objects.requireNonNull(method, "notification method cannot be null");
    }

    public static JsonRpcNotification of(String method, JsonNode params) {
        return new JsonRpcNotification(McpProtocolConstants.JSONRPC_VERSION, method, params);
    }
}
