package com.agent.agentcorelab.eval.report;

import com.agent.agentcorelab.eval.dataset.EvalVersions;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Complete evaluation report aggregating run configurations, metrics, and case results.
 */
public record EvalReport(
        String reportId,
        Instant generatedAt,
        String environment,
        EvalVersions versions,
        EvalMetrics metrics,
        List<EvalCaseResult> caseResults
) {
    public EvalReport {
        caseResults = caseResults == null ? Collections.emptyList() : List.copyOf(caseResults);
    }
}
