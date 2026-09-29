package com.agent.agentcorelab.mcp.transport;

import com.agent.agentcorelab.mcp.protocol.JsonRpcError;
import com.agent.agentcorelab.mcp.protocol.JsonRpcMessage;
import com.agent.agentcorelab.mcp.protocol.JsonRpcNotification;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;
import com.agent.agentcorelab.tool.ToolJson;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * In-memory transport implementation for testing and embedded local mock servers.
 */
public final class InMemoryMcpTransport implements McpTransport {

    private volatile Function<JsonRpcRequest, JsonRpcResponse> requestHandler;
    private volatile Consumer<JsonRpcNotification> notificationHandler;
    private Consumer<JsonRpcMessage> incomingMessageHandler;
    private volatile boolean alive = true;

    public InMemoryMcpTransport() {
        this(req -> JsonRpcResponse.success(req.id(), ToolJson.parse("{}")));
    }

    public InMemoryMcpTransport(Function<JsonRpcRequest, JsonRpcResponse> requestHandler) {
        this(requestHandler, n -> {});
    }

    public InMemoryMcpTransport(Function<JsonRpcRequest, JsonRpcResponse> requestHandler,
                                Consumer<JsonRpcNotification> notificationHandler) {
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler cannot be null");
        this.notificationHandler = Objects.requireNonNull(notificationHandler, "notificationHandler cannot be null");
    }

    public InMemoryMcpTransport setRequestHandler(Function<JsonRpcRequest, JsonRpcResponse> handler) {
        this.requestHandler = Objects.requireNonNull(handler, "handler cannot be null");
        return this;
    }

    public InMemoryMcpTransport setResponder(Function<JsonRpcMessage, JsonRpcResponse> responder) {
        Objects.requireNonNull(responder, "responder cannot be null");
        this.requestHandler = req -> responder.apply(req);
        return this;
    }

    @Override
    public void start(Consumer<JsonRpcMessage> incomingMessageHandler) {
        this.incomingMessageHandler = incomingMessageHandler;
        this.alive = true;
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        if (!alive) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport is closed"));
        }
        try {
            Function<JsonRpcRequest, JsonRpcResponse> handler = this.requestHandler;
            JsonRpcResponse response = handler != null ? handler.apply(request) : null;
            if (incomingMessageHandler != null && response != null) {
                incomingMessageHandler.accept(response);
            }
            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            JsonRpcResponse errResp = JsonRpcResponse.failure(request.id(),
                    new JsonRpcError(McpProtocolConstants.INTERNAL_ERROR, e.getMessage()));
            return CompletableFuture.completedFuture(errResp);
        }
    }

    @Override
    public void sendNotification(JsonRpcNotification notification) {
        if (alive && notificationHandler != null) {
            notificationHandler.accept(notification);
        }
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void close() {
        alive = false;
    }
}
