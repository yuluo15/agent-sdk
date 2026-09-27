package com.agent.agentcorelab.model;

import com.agent.agentcorelab.tool.ToolOutcome;

import java.time.Duration;

// The adapter retains native messages and metadata; only the Runner executes tools.
public interface ModelSession extends AutoCloseable {

    ModelResponse nextResponse(Duration timeout);

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
