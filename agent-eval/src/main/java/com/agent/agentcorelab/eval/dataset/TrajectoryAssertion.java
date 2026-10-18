package com.agent.agentcorelab.eval.dataset;

import java.util.Collections;
import java.util.List;

/**
 * Trajectory assertions using partial order (DAG) rules and set constraints.
 */
public record TrajectoryAssertion(
        List<String> requiredTools,
        List<String> forbiddenTools,
        List<String> orderingRules,
        List<String> allowedExtraTools,
        int maxRepeatedCalls
) {
    public TrajectoryAssertion {
        requiredTools = requiredTools == null ? Collections.emptyList() : List.copyOf(requiredTools);
        forbiddenTools = forbiddenTools == null ? Collections.emptyList() : List.copyOf(forbiddenTools);
        orderingRules = orderingRules == null ? Collections.emptyList() : List.copyOf(orderingRules);
        allowedExtraTools = allowedExtraTools == null ? Collections.emptyList() : List.copyOf(allowedExtraTools);
        if (maxRepeatedCalls <= 0) {
            maxRepeatedCalls = 2;
        }
    }

    public static TrajectoryAssertion empty() {
        return new TrajectoryAssertion(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 2);
    }
}
