package com.agent.agentcorelab.agent;

import java.time.Duration;

public record RunLimits(
        int maxModelRequests,
        int maxToolCalls,
        Duration modelTimeout,
        Duration activeTimeout,
        int maxContextTokens,
        int outputTokenReserve,
        int maxRepeatedToolCalls,
        int maxConsecutiveToolErrors,
        int maxToolResultBytes
) {
    public RunLimits(int maxModelRequests, int maxToolCalls, Duration modelTimeout, Duration activeTimeout) {
        this(maxModelRequests, maxToolCalls, modelTimeout, activeTimeout, 32768, 4096, 3, 3, 16384);
    }

    public RunLimits {
        if (maxModelRequests <= 0 || maxToolCalls <= 0 || maxContextTokens <= 0
                || outputTokenReserve <= 0 || outputTokenReserve >= maxContextTokens
                || maxRepeatedToolCalls < 2 || maxConsecutiveToolErrors <= 0 || maxToolResultBytes < 256) {
            throw new IllegalArgumentException("Request, context and tool limits must be valid positive values");
        }
        if (modelTimeout == null || modelTimeout.isNegative() || modelTimeout.isZero()
                || activeTimeout == null || activeTimeout.isNegative() || activeTimeout.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
    }
}
