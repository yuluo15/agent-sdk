package com.agent.agentcorelab.eval.assertion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of an assertion evaluation, detailing pass/fail status and violation codes.
 */
public record AssertionOutcome(
        boolean passed,
        List<FailureCode> failureCodes,
        List<String> details
) {
    public AssertionOutcome {
        failureCodes = failureCodes == null ? Collections.emptyList() : List.copyOf(failureCodes);
        details = details == null ? Collections.emptyList() : List.copyOf(details);
    }

    public static AssertionOutcome pass() {
        return new AssertionOutcome(true, Collections.emptyList(), Collections.emptyList());
    }

    public static AssertionOutcome fail(FailureCode code, String detail) {
        return new AssertionOutcome(false, List.of(code), List.of(detail));
    }

    public static AssertionOutcome combine(List<AssertionOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return pass();
        }
        boolean allPassed = true;
        List<FailureCode> codes = new ArrayList<>();
        List<String> details = new ArrayList<>();

        for (AssertionOutcome outcome : outcomes) {
            if (!outcome.passed()) {
                allPassed = false;
                codes.addAll(outcome.failureCodes());
                details.addAll(outcome.details());
            }
        }
        return new AssertionOutcome(allPassed, codes, details);
    }

    public boolean hasP0SafetyBlocker() {
        return failureCodes.stream().anyMatch(FailureCode::isP0SafetyBlocker);
    }
}
