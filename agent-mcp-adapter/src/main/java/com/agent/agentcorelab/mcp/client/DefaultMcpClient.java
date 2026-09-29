package com.agent.agentcorelab.mcp.client;

import com.agent.agentcorelab.mcp.protocol.JsonRpcNotification;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.McpInitializeResult;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standard implementation of {@link McpClient} managing protocol handshake and RPC execution.
 */
public final class DefaultMcpClient implements McpClient {

    private enum State {
        CREATED,
        INITIALIZING,
        READY,
        CLOSED
    }

    private final String serverName;
    private final McpTransport transport;
    private final String clientName;
    private final String clientVersion;

    private final AtomicLong idCounter = new AtomicLong(1);
    private final AtomicReference<State> state = new AtomicReference<>(State.CREATED);
    private volatile McpInitializeResult initializeResult;

    public DefaultMcpClient(String serverName, McpTransport transport) {
        this(serverName, transport, "agent-sdk", "1.1.0");
    }

    public DefaultMcpClient(String serverName, McpTransport transport, String clientName, String clientVersion) {
        this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
        this.transport = Objects.requireNonNull(transport, "transport cannot be null");
        this.clientName = clientName != null ? clientName : "agent-sdk";
        this.clientVersion = clientVersion != null ? clientVersion : "1.1.0";
    }

    @Override
    public String serverName() {
        return serverName;
    }

    private String nextId() {
        return serverName + "-" + idCounter.getAndIncrement();
    }

    @Override
    public McpInitializeResult initialize() {
        try {
            return initializeAsync().join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize MCP client [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<McpInitializeResult> initializeAsync() {
        if (!state.compareAndSet(State.CREATED, State.INITIALIZING)) {
            if (state.get() == State.READY) {
                return CompletableFuture.completedFuture(initializeResult);
            }
            return CompletableFuture.failedFuture(new IllegalStateException("Client in invalid state: " + state.get()));
        }

        transport.start(msg -> {
            // Optional incoming notification handling
        });

        JsonNode initParams = McpJsonRpcCodec.createInitializeParams(clientName, clientVersion);
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpProtocolConstants.METHOD_INITIALIZE, initParams);

        return transport.send(req).thenApply(resp -> {
            if (!resp.isSuccess()) {
                state.set(State.CLOSED);
                throw new IllegalStateException("MCP initialize rejected by server: " + resp.error().message());
            }

            McpInitializeResult result = McpJsonRpcCodec.decodeInitializeResult(resp.result());
            this.initializeResult = result;

            // Send standard initialized notification
            transport.sendNotification(JsonRpcNotification.of(McpProtocolConstants.METHOD_NOTIFICATIONS_INITIALIZED, null));
            state.set(State.READY);
            return result;
        });
    }

    @Override
    public List<McpTool> listTools() {
        try {
            return listToolsAsync().join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list tools for MCP client [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<McpTool>> listToolsAsync() {
        ensureReady();
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpProtocolConstants.METHOD_TOOLS_LIST, null);
        return transport.send(req).thenApply(resp -> {
            if (!resp.isSuccess()) {
                throw new IllegalStateException("Failed to list tools: " + resp.error().message());
            }
            return McpJsonRpcCodec.decodeToolsList(resp.result());
        });
    }

    @Override
    public McpToolResult callTool(String toolName, JsonNode arguments) {
        try {
            return callToolAsync(toolName, arguments).join();
        } catch (Exception e) {
            return McpToolResult.failure("MCP tool call error: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<McpToolResult> callToolAsync(String toolName, JsonNode arguments) {
        ensureReady();
        JsonNode params = McpJsonRpcCodec.createToolCallParams(toolName, arguments);
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpProtocolConstants.METHOD_TOOLS_CALL, params);

        return transport.send(req).thenApply(resp -> {
            if (!resp.isSuccess()) {
                return McpToolResult.failure("MCP error [" + resp.error().code() + "]: " + resp.error().message());
            }
            return McpJsonRpcCodec.decodeToolResult(resp.result());
        });
    }

    @Override
    public Duration ping() {
        ensureReady();
        Instant start = Instant.now();
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpProtocolConstants.METHOD_PING, null);
        try {
            transport.send(req).join();
            return Duration.between(start, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Ping failed to MCP server [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return state.get() == State.READY && transport.isAlive();
    }

    @Override
    public void close() {
        state.set(State.CLOSED);
        transport.close();
    }

    private void ensureReady() {
        if (state.get() != State.READY) {
            throw new IllegalStateException("MCP client [" + serverName + "] is not initialized. Current state: " + state.get());
        }
    }
}
