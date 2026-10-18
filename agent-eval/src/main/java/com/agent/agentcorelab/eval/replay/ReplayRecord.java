package com.agent.agentcorelab.eval.replay;

import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.tool.ToolCall;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistable snapshot of a single recorded model response indexed by canonical request fingerprint.
 */
public record ReplayRecord(
        String fingerprint,
        String text,
        List<ToolCall> toolCalls,
        ModelResponse.FinishReason finishReason,
        Long inputTokens,
        Long outputTokens,
        Instant recordedAt
) {
    public ReplayRecord {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        recordedAt = recordedAt == null ? Instant.now() : recordedAt;
    }

    public ModelResponse toModelResponse() {
        Optional<ModelResponse.TokenUsage> usage = (inputTokens != null && outputTokens != null)
                ? Optional.of(new ModelResponse.TokenUsage(inputTokens, outputTokens))
                : Optional.empty();
        return new ModelResponse(text, toolCalls, finishReason, usage);
    }

    public static ReplayRecord fromModelResponse(String fingerprint, ModelResponse response) {
        Long inTokens = response.usage().map(ModelResponse.TokenUsage::inputTokens).orElse(null);
        Long outTokens = response.usage().map(ModelResponse.TokenUsage::outputTokens).orElse(null);
        return new ReplayRecord(
                fingerprint,
                response.text(),
                response.toolCalls(),
                response.finishReason(),
                inTokens,
                outTokens,
                Instant.now()
        );
    }
}
