package com.agent.agentcorelab.model;

import com.agent.agentcorelab.tool.ToolCall;

import java.util.List;
import java.util.Optional;

public record ModelResponse(
        String text,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        Optional<TokenUsage> usage
) {
    public ModelResponse {
        toolCalls = List.copyOf(toolCalls);
    }

    public enum FinishReason {
        STOP,
        TOOL_CALLS,
        LENGTH,
        REFUSED,
        ERROR
    }

    public record TokenUsage(long inputTokens, long outputTokens) {
    }
}
