package com.agent.agentcorelab.eval.report;

import com.agent.agentcorelab.eval.assertion.FailureCode;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Result of evaluating a single test case.
 */
public record EvalCaseResult(
        String caseId,
        String title,
        String category,
        String dataSource,
        boolean passed,
        String status,
        List<FailureCode> failureCodes,
        List<String> failureDetails,
        List<String> executedTools,
        Set<String> citedEvidenceIds,
        long durationMs,
        int modelRequests,
        int toolCalls,
        long inputTokens,
        long outputTokens,
        double estimatedCostUsd,
        String finalOutput
) {
    public EvalCaseResult {
        failureCodes = failureCodes == null ? Collections.emptyList() : List.copyOf(failureCodes);
        failureDetails = failureDetails == null ? Collections.emptyList() : List.copyOf(failureDetails);
        executedTools = executedTools == null ? Collections.emptyList() : List.copyOf(executedTools);
        citedEvidenceIds = citedEvidenceIds == null ? Collections.emptySet() : Set.copyOf(citedEvidenceIds);
    }

    public boolean hasP0SafetyBlocker() {
        return failureCodes.stream().anyMatch(FailureCode::isP0SafetyBlocker);
    }
}
