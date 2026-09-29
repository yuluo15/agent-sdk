package com.agent.agentcorelab.mcp.protocol;

/**
 * Base marker interface for all JSON-RPC 2.0 protocol message frames.
 */
public sealed interface JsonRpcMessage permits JsonRpcRequest, JsonRpcResponse, JsonRpcNotification {
    String jsonrpc();
}
