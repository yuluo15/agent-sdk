package com.agent.agentcorelab.mcp.transport;

import com.agent.agentcorelab.mcp.protocol.JsonRpcMessage;
import com.agent.agentcorelab.mcp.protocol.JsonRpcNotification;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Underlying transport layer SPI responsible for bi-directional communication
 * with an MCP server via stdio, HTTP/SSE, or in-memory pipes.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Initializes and starts the transport connection, registering a consumer for incoming messages.
     *
     * @param incomingMessageHandler consumer that receives incoming frames (responses, requests, notifications)
     */
    void start(Consumer<JsonRpcMessage> incomingMessageHandler);

    /**
     * Sends a request to the MCP server and returns a future completing with the response.
     *
     * @param request JSON-RPC request frame
     * @return CompletableFuture holding the server's response
     */
    CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request);

    /**
     * Sends a fire-and-forget notification to the MCP server.
     *
     * @param notification JSON-RPC notification frame
     */
    void sendNotification(JsonRpcNotification notification);

    /**
     * Checks if the transport is active, connected, and healthy.
     */
    boolean isAlive();

    /**
     * Returns the underlying process handle if this transport manages an external OS process.
     */
    default Optional<ProcessHandle> processHandle() {
        return Optional.empty();
    }

    /**
     * Returns the OS process ID if this transport manages an external OS process.
     */
    default Optional<Long> pid() {
        return processHandle().map(ProcessHandle::pid);
    }

    /**
     * Closes the transport, destroying underlying processes, sockets, and threads.
     */
    @Override
    void close();
}
