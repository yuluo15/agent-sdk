package com.agent.agentcorelab.eval.dataset;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Domain model representing an evaluation case conforming to the Phase 0 Schema specification.
 */
public record EvalCase(
        String caseId,
        String title,
        String category,
        String dataSource,
        String goal,
        Map<String, Object> context,
        List<String> allowedTools,
        List<String> forbiddenTools,
        String expectedStatus,
        TrajectoryAssertion trajectoryAssertion,
        EvidenceAssertion evidenceAssertion,
        List<String> forbiddenContent,
        EvalBudgets budgets,
        EvalVersions versions,
        String notes
) {
    public EvalCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId is required");
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required for case: " + caseId);
        }
        category = (category == null || category.isBlank()) ? "normal" : category;
        dataSource = (dataSource == null || dataSource.isBlank()) ? "synthetic" : dataSource;
        context = context == null ? Collections.emptyMap() : Map.copyOf(context);
        allowedTools = allowedTools == null ? Collections.emptyList() : List.copyOf(allowedTools);
        forbiddenTools = forbiddenTools == null ? Collections.emptyList() : List.copyOf(forbiddenTools);
        expectedStatus = (expectedStatus == null || expectedStatus.isBlank()) ? "COMPLETED" : expectedStatus;
        trajectoryAssertion = trajectoryAssertion == null ? TrajectoryAssertion.empty() : trajectoryAssertion;
        evidenceAssertion = evidenceAssertion == null ? EvidenceAssertion.empty() : evidenceAssertion;
        forbiddenContent = forbiddenContent == null ? Collections.emptyList() : List.copyOf(forbiddenContent);
        budgets = budgets == null ? EvalBudgets.defaultBudgets() : budgets;
        versions = versions == null ? EvalVersions.empty() : versions;
        notes = notes == null ? "" : notes;
    }
}
