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
     * <b>Important Notice on Streaming Semantics:</b> The default implementation (and the built-in
     * {@code SpringAiModelGateway}) executes {@link #nextResponse(Duration)} synchronously to gather the
     * full response, and then emits the entire text as a single chunk to {@code chunkListener}. It does
     * <b>not</b> currently emit incremental token-by-token chunks during generation. Adapters requiring
     * true token-level streaming should override this method.
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
