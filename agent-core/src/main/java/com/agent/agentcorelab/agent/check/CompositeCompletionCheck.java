package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.run.RunSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Composite completion check combining multiple {@link CompletionCheck} implementations.
 */
public final class CompositeCompletionCheck implements CompletionCheck {
    private final List<CompletionCheck> checks;
    private final boolean requireAll;

    private CompositeCompletionCheck(List<CompletionCheck> checks, boolean requireAll) {
        this.checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
        this.requireAll = requireAll;
    }

    public static CompositeCompletionCheck all(CompletionCheck... checks) {
        return new CompositeCompletionCheck(List.of(checks), true);
    }

    public static CompositeCompletionCheck all(List<CompletionCheck> checks) {
        return new CompositeCompletionCheck(checks, true);
    }

    public static CompositeCompletionCheck any(CompletionCheck... checks) {
        return new CompositeCompletionCheck(List.of(checks), false);
    }

    public static CompositeCompletionCheck any(List<CompletionCheck> checks) {
        return new CompositeCompletionCheck(checks, false);
    }

    @Override
    public Result check(RunSnapshot run, String finalAnswer) {
        if (checks.isEmpty()) {
            return Result.satisfied();
        }
        if (requireAll) {
            for (CompletionCheck check : checks) {
                Result r = check.check(run, finalAnswer);
                if (!r.complete()) {
                    return r;
                }
            }
            return Result.satisfied();
        } else {
            StringBuilder feedbacks = new StringBuilder();
            for (CompletionCheck check : checks) {
                Result r = check.check(run, finalAnswer);
                if (r.complete()) {
                    return r;
                }
                if (!r.feedback().isBlank()) {
                    if (!feedbacks.isEmpty()) {
                        feedbacks.append("; ");
                    }
                    feedbacks.append(r.feedback());
                }
            }
            return Result.incomplete("None of the alternative checks passed: " + feedbacks);
        }
    }
}
