package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.RunLimits;

import java.util.Objects;
import java.util.Set;

public record AgentSpec(
        String id,
        String instructions,
        Set<String> toolNames,
        RunLimits limits,
        CompletionCheck completionCheck,
        ExecutionPolicy policy
) {
    public AgentSpec(String id, String instructions, Set<String> toolNames, RunLimits limits, CompletionCheck completionCheck) {
        this(id, instructions, toolNames, limits, completionCheck, ExecutionPolicy.strict());
    }

    public AgentSpec {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")
                || instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Agent id and instructions are required");
        }
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(completionCheck, "completionCheck");
        Objects.requireNonNull(policy, "policy");
        toolNames = Set.copyOf(toolNames);
    }
}
