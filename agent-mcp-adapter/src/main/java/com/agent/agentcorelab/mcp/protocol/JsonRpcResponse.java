package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard JSON-RPC 2.0 response message frame.
 */
public record JsonRpcResponse(
        String jsonrpc,
        String id,
        JsonNode result,
        JsonRpcError error
) implements JsonRpcMessage {

    public JsonRpcResponse {
        jsonrpc = jsonrpc != null ? jsonrpc : McpProtocolConstants.JSONRPC_VERSION;
        Objects.requireNonNull(id, "response id cannot be null");
    }

    public boolean isSuccess() {
        return error == null;
    }

    public static JsonRpcResponse success(String id, JsonNode result) {
        return new JsonRpcResponse(McpProtocolConstants.JSONRPC_VERSION, id, result, null);
    }

    public static JsonRpcResponse failure(String id, JsonRpcError error) {
        return new JsonRpcResponse(McpProtocolConstants.JSONRPC_VERSION, id, null, error);
    }

    public static JsonRpcResponse error(String id, JsonRpcError error) {
        return failure(id, error);
    }
}
