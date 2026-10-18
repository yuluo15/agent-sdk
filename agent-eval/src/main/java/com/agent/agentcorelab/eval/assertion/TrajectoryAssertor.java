package com.agent.agentcorelab.eval.assertion;

import com.agent.agentcorelab.eval.dataset.TrajectoryAssertion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates tool trajectory compliance against DAG partial order rules and set constraints.
 */
public final class TrajectoryAssertor {

    public record ToolInvocation(String toolName, String argumentsJson) {
    }

    private TrajectoryAssertor() {
    }

    /**
     * Evaluates trajectory compliance.
     *
     * @param assertion trajectory assertion configuration
     * @param invocations recorded tool calls in chronological execution order
     * @return assertion outcome
     */
    public static AssertionOutcome evaluate(TrajectoryAssertion assertion, List<ToolInvocation> invocations) {
        if (assertion == null) {
            return AssertionOutcome.pass();
        }

        List<FailureCode> codes = new ArrayList<>();
        List<String> details = new ArrayList<>();

        List<String> executedToolNames = invocations.stream().map(ToolInvocation::toolName).toList();
        Set<String> executedSet = new HashSet<>(executedToolNames);

        // 1. Check required tools
        for (String required : assertion.requiredTools()) {
            if (!executedSet.contains(required)) {
                codes.add(FailureCode.TRAJECTORY_MISSING_TOOL);
                details.add("Required tool was not executed: " + required);
            }
        }

        // 2. Check allowed extra tools (if allowedExtraTools is defined and extra tools exist)
        Set<String> permittedExtra = new HashSet<>(assertion.allowedExtraTools());
        Set<String> requiredSet = new HashSet<>(assertion.requiredTools());
        for (String executed : executedSet) {
            if (!requiredSet.contains(executed) && !permittedExtra.contains(executed)) {
                codes.add(FailureCode.TRAJECTORY_EXTRA_TOOL);
                details.add("Unexpected tool executed outside required or allowedExtra set: " + executed);
            }
        }

        // 3. Check partial order DAG rules: "ToolA MUST_BEFORE ToolB"
        for (String rule : assertion.orderingRules()) {
            String[] parts = rule.split("\\s+MUST_BEFORE\\s+");
            if (parts.length == 2) {
                String toolA = parts[0].trim();
                String toolB = parts[1].trim();

                int firstIndexB = executedToolNames.indexOf(toolB);
                if (firstIndexB != -1) {
                    int firstIndexA = executedToolNames.indexOf(toolA);
                    if (firstIndexA == -1 || firstIndexA > firstIndexB) {
                        codes.add(FailureCode.TRAJECTORY_WRONG_ORDER);
                        details.add("Ordering violation for rule '" + rule + "': " + toolA
                                + " must be executed before " + toolB + " (A index=" + firstIndexA + ", B index=" + firstIndexB + ")");
                    }
                }
            }
        }

        // 4. Check repeated invocations with identical arguments
        Map<String, Integer> invocationCounts = new HashMap<>();
        for (ToolInvocation inv : invocations) {
            String key = inv.toolName() + ":" + (inv.argumentsJson() == null ? "" : inv.argumentsJson().trim());
            int count = invocationCounts.merge(key, 1, Integer::sum);
            if (count > assertion.maxRepeatedCalls()) {
                codes.add(FailureCode.TRAJECTORY_REPEAT_NO_PROGRESS);
                details.add("Identical tool invocation exceeded maxRepeatedCalls (" + assertion.maxRepeatedCalls() + "): " + key);
                break;
            }
        }

        boolean passed = codes.isEmpty();
        return new AssertionOutcome(passed, codes, details);
    }
}
