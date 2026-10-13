package com.agent.agentcorelab.model;

import com.agent.agentcorelab.tool.ToolOutcome;

import java.time.Duration;

// The adapter retains native messages and metadata; only the Runner executes tools.
public interface ModelSession extends AutoCloseable {

    /**
     * Retrieves the next response from the model provider within the specified timeout.
     *
     * @param timeout maximum duration to wait for the model response
     * @return the model response containing text, thoughts, or tool calls
     */
    ModelResponse nextResponse(Duration timeout);

    /**
     * Retrieves the next response from the model provider, optionally dispatching chunks to a listener.
     * <p>
     * <b>Streaming Semantics:</b> Built-in adapters such as {@code SpringAiModelGateway} request
     * SSE streaming ({@code "stream": true}) when {@code chunkListener} is non-null and emit
     * incremental {@link ModelStreamChunk.Type#THINKING} and {@link ModelStreamChunk.Type#TEXT} chunks
     * in real time as SSE events arrive, with automatic fallback to single-chunk emission if the
     * upstream server returns a non-SSE {@code application/json} response. The default interface method
     * below provides a fallback for custom {@link ModelSession} implementations that only implement
     * {@link #nextResponse(Duration)}.
     * </p>
     *
     * @param timeout maximum duration to wait for the model response
     * @param chunkListener consumer for model stream chunks (may be null)
     * @return the complete model response
     */
    default ModelResponse nextResponse(Duration timeout, java.util.function.Consumer<ModelStreamChunk> chunkListener) {
        ModelResponse response = nextResponse(timeout);
        if (chunkListener != null && response != null && response.text() != null && !response.text().isEmpty()) {
            chunkListener.accept(ModelStreamChunk.text(response.text()));
        }
        return response;
    }

    void appendToolResult(String callId, ToolOutcome outcome);

    void appendUserMessage(String message);

    void appendRuntimeFeedback(String feedback);

    /** Conservative estimate of all native messages and tool schemas, excluding reserved output. */
    long estimatedInputTokens();

    /**
     * Attempts to compact earlier historical context (e.g. intermediate tool outcomes) to fit within
     * the target token budget, preserving system instructions, goal, and recent turns.
     *
     * @param targetTokenBudget desired maximum input tokens
     * @return true if context was compacted and token count was reduced
     */
    default boolean compactContext(long targetTokenBudget) {
        return false;
    }

    @Override
    void close();
}
