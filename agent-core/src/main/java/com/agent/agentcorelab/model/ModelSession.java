package com.agent.agentcorelab.model;

import com.agent.agentcorelab.tool.ToolOutcome;

import java.time.Duration;

// The adapter retains native messages and metadata; only the Runner executes tools.
public interface ModelSession extends AutoCloseable {

    ModelResponse nextResponse(Duration timeout);

    void appendToolResult(String callId, ToolOutcome outcome);

    void appendUserMessage(String message);

    void appendRuntimeFeedback(String feedback);

    /** Conservative estimate of all native messages and tool schemas, excluding reserved output. */
    long estimatedInputTokens();

    @Override
    void close();
}
