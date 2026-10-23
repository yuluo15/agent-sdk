package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard JSON-RPC 2.0 request message frame.
 */
public record JsonRpcRequest(
        String jsonrpc,
        String id,
        String method,
        JsonNode params
) implements JsonRpcMessage {

    public JsonRpcRequest {
        jsonrpc = jsonrpc != null ? jsonrpc : McpProtocolConstants.JSONRPC_VERSION;
        Objects.requireNonNull(id, "request id cannot be null");
        Objects.requireNonNull(method, "method cannot be null");
    }

    public static JsonRpcRequest of(String id, String method, JsonNode params) {
        return new JsonRpcRequest(McpProtocolConstants.JSONRPC_VERSION, id, method, params);
    }
}
