package com.agent.agentcorelab.agent;

import java.util.Objects;
import java.util.Set;

public record AgentSpec(
        String id,
        String instructions,
        Set<String> toolNames,
        RunLimits limits,
        CompletionCheck completionCheck
) {
    public AgentSpec {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")
                || instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Agent id and instructions are required");
        }
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(completionCheck, "completionCheck");
        toolNames = Set.copyOf(toolNames);
    }
}
