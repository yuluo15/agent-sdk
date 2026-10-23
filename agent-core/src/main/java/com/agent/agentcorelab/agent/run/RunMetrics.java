package com.agent.agentcorelab.agent.run;

import com.agent.agentcorelab.model.ModelResponse.TokenUsage;

import java.time.Duration;
import java.util.Optional;

/**
 * Execution metrics for an agent run, breaking down active execution time,
 * model inference time, tool execution time, and human waiting time.
 *
 * @param modelRequests total number of model inference requests made
 * @param toolCalls     total number of tool calls attempted
 * @param activeTime    total active processing duration (excluding waiting states)
 * @param modelTime     cumulative duration spent waiting for model responses
 * @param toolTime      cumulative duration spent executing tools
 * @param waitingTime   cumulative duration spent in waiting states (WAITING_INPUT or WAITING_APPROVAL)
 * @param tokenUsage    aggregated token usage if reported by every model request, otherwise empty
 */
public record RunMetrics(
        int modelRequests,
        int toolCalls,
        Duration activeTime,
        Duration modelTime,
        Duration toolTime,
        Duration waitingTime,
        Optional<TokenUsage> tokenUsage
) {

    public RunMetrics {
        if (modelRequests < 0) {
            throw new IllegalArgumentException("modelRequests must not be negative");
        }
        if (toolCalls < 0) {
            throw new IllegalArgumentException("toolCalls must not be negative");
        }
        activeTime = activeTime != null ? activeTime : Duration.ZERO;
        modelTime = modelTime != null ? modelTime : Duration.ZERO;
        toolTime = toolTime != null ? toolTime : Duration.ZERO;
        waitingTime = waitingTime != null ? waitingTime : Duration.ZERO;
        tokenUsage = tokenUsage != null ? tokenUsage : Optional.empty();
    }

    /**
     * Backward-compatible constructor defaulting segmented durations ({@code modelTime}, {@code toolTime},
     * {@code waitingTime}) to {@link Duration#ZERO}.
     */
    public RunMetrics(int modelRequests, int toolCalls, Duration activeTime, Optional<TokenUsage> tokenUsage) {
        this(modelRequests, toolCalls, activeTime, Duration.ZERO, Duration.ZERO, Duration.ZERO, tokenUsage);
    }
}
