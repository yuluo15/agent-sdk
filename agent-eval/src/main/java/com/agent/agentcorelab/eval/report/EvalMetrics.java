package com.agent.agentcorelab.eval.report;

import com.agent.agentcorelab.eval.assertion.FailureCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated statistical metrics conforming to Section 5.4 and 5.5 of Phase 0 specification.
 */
public record EvalMetrics(
        int totalCases,
        int passedCases,
        int failedCases,
        int safetyFailures,
        int forbiddenToolAttempts,
        double taskSuccessRate,
        double trajectoryAccuracy,
        double evidenceLandingRate,
        double correctClarificationRate,
        long p50LatencyMs,
        long p95LatencyMs,
        double totalCostUsd
) {

    public static EvalMetrics compute(List<EvalCaseResult> results) {
        if (results == null || results.isEmpty()) {
            return new EvalMetrics(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0);
        }

        int total = results.size();
        int passed = 0;
        int safetyFailures = 0;
        int forbiddenAttempts = 0;
        int trajectoryPassed = 0;
        int evidencePassed = 0;
        int clarificationEligible = 0;
        int clarificationPassed = 0;
        double totalCost = 0.0;
        List<Long> latencies = new ArrayList<>();

        for (EvalCaseResult r : results) {
            if (r.passed()) {
                passed++;
            }
            if (r.hasP0SafetyBlocker()) {
                safetyFailures++;
            }
            if (r.failureCodes().contains(FailureCode.SAFETY_FORBIDDEN_TOOL_ATTEMPTED)) {
                forbiddenAttempts++;
            }

            boolean hasTrajectoryFailure = r.failureCodes().stream().anyMatch(c ->
                    c == FailureCode.TRAJECTORY_MISSING_TOOL ||
                    c == FailureCode.TRAJECTORY_WRONG_ARGS ||
                    c == FailureCode.TRAJECTORY_WRONG_ORDER ||
                    c == FailureCode.TRAJECTORY_REPEAT_NO_PROGRESS ||
                    c == FailureCode.TRAJECTORY_EXTRA_TOOL
            );
            if (!hasTrajectoryFailure) {
                trajectoryPassed++;
            }

            boolean hasEvidenceFailure = r.failureCodes().stream().anyMatch(c ->
                    c == FailureCode.EVIDENCE_MISSING ||
                    c == FailureCode.EVIDENCE_CONTRADICTION ||
                    c == FailureCode.EVIDENCE_FABRICATED ||
                    c == FailureCode.CITATION_FORMAT_MISSING
            );
            if (!hasEvidenceFailure) {
                evidencePassed++;
            }

            if ("clarification".equalsIgnoreCase(r.category()) || "missing_args".equalsIgnoreCase(r.category())) {
                clarificationEligible++;
                if (!r.failureCodes().contains(FailureCode.CLARIFICATION_MISSED)) {
                    clarificationPassed++;
                }
            }

            latencies.add(r.durationMs());
            totalCost += r.estimatedCostUsd();
        }

        Collections.sort(latencies);
        long p50 = latencies.get((int) Math.floor(latencies.size() * 0.50));
        long p95 = latencies.get((int) Math.min(latencies.size() - 1, Math.floor(latencies.size() * 0.95)));

        double successRate = (double) passed / total * 100.0;
        double trajectoryRate = (double) trajectoryPassed / total * 100.0;
        double evidenceRate = (double) evidencePassed / total * 100.0;
        double clarificationRate = clarificationEligible > 0 ? (double) clarificationPassed / clarificationEligible * 100.0 : 100.0;

        return new EvalMetrics(
                total,
                passed,
                total - passed,
                safetyFailures,
                forbiddenAttempts,
                successRate,
                trajectoryRate,
                evidenceRate,
                clarificationRate,
                p50,
                p95,
                totalCost
        );
    }
}
