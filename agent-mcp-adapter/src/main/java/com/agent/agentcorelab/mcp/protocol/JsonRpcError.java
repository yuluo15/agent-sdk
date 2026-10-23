package com.agent.agentcorelab.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Standard JSON-RPC 2.0 error object.
 */
public record JsonRpcError(int code, String message, JsonNode data) {

    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    public static JsonRpcError invalidParams(String message) {
        return new JsonRpcError(McpProtocolConstants.INVALID_PARAMS, message);
    }

    public static JsonRpcError methodNotFound(String method) {
        return new JsonRpcError(McpProtocolConstants.METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static JsonRpcError internalError(String message) {
        return new JsonRpcError(McpProtocolConstants.INTERNAL_ERROR, message);
    }
}
