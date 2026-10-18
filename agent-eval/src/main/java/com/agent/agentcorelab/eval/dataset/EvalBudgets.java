package com.agent.agentcorelab.eval.dataset;

/**
 * Resource and execution budgets for a single evaluation case.
 */
public record EvalBudgets(
        int maxModelRequests,
        int maxToolCalls,
        double maxCostUsd,
        long maxLatencyMs
) {
    public static EvalBudgets defaultBudgets() {
        return new EvalBudgets(16, 30, 0.10, 60_000L);
    }
}
