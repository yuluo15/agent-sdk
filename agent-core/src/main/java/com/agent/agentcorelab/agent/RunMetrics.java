package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.model.ModelResponse.TokenUsage;

import java.time.Duration;
import java.util.Optional;

public record RunMetrics(int modelRequests, int toolCalls, Duration activeTime, Optional<TokenUsage> tokenUsage) {
}
