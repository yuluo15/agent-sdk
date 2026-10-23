package com.agent.agentcorelab.agent.plan;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit multi-step task plan maintained and revised across long-running turns.
 */
public record TaskPlan(
        int planRevision,
        int goalVersion,
        String reason,
        List<TaskStep> steps
) {
    public static final int MAX_STEPS = 10;

    public TaskPlan {
        if (planRevision < 0) {
            throw new IllegalArgumentException("planRevision must be non-negative, got: " + planRevision);
        }
        if (goalVersion <= 0) {
            throw new IllegalArgumentException("goalVersion must be positive, got: " + goalVersion);
        }
        reason = reason != null ? reason : "";
        steps = List.copyOf(steps != null ? steps : List.of());
        if (steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Maximum steps allowed is " + MAX_STEPS + ", got: " + steps.size());
        }
    }

    public static TaskPlan empty(int goalVersion) {
        return new TaskPlan(0, goalVersion, "Initial empty plan", List.of());
    }

    public static TaskPlan forRevisedGoal(int newGoalVersion) {
        return new TaskPlan(0, newGoalVersion, "Plan reset for revised goal v" + newGoalVersion, List.of());
    }

    public record ValidationResult(boolean valid, String code, String message) {
        public static ValidationResult ok() {
            return new ValidationResult(true, "OK", "");
        }

        public static ValidationResult failure(String code, String message) {
            return new ValidationResult(false, code, message);
        }
    }

    /**
     * Validates an update attempt according to docs/05 & docs/06:
     * - expectedRevision == current planRevision (CAS check)
     * - targetGoalVersion == current goalVersion
     * - steps size <= MAX_STEPS
     * - unique step IDs
     * - at most one ACTIVE step
     * - DONE steps must have non-empty evidenceRefs and all evidenceRefs must exist in knownEvidenceIds
     * - DROPPED steps must have a non-empty reason
     */
    public ValidationResult validateUpdate(
            int expectedRevision,
            int targetGoalVersion,
            List<TaskStep> newSteps,
            Set<String> knownEvidenceIds
    ) {
        if (expectedRevision != this.planRevision) {
            return ValidationResult.failure("REVISION_MISMATCH",
                    "Expected planRevision " + this.planRevision + ", but received " + expectedRevision);
        }
        if (targetGoalVersion != this.goalVersion) {
            return ValidationResult.failure("GOAL_VERSION_MISMATCH",
                    "Target goalVersion " + targetGoalVersion + " does not match current plan goalVersion " + this.goalVersion);
        }
        if (newSteps == null || newSteps.isEmpty()) {
            return ValidationResult.failure("EMPTY_PLAN", "Plan must contain at least one step");
        }
        if (newSteps.size() > MAX_STEPS) {
            return ValidationResult.failure("STEP_LIMIT_EXCEEDED",
                    "Maximum steps allowed is " + MAX_STEPS + ", received: " + newSteps.size());
        }

        Set<String> stepIds = new HashSet<>();
        int activeCount = 0;
        for (TaskStep step : newSteps) {
            if (!stepIds.add(step.stepId())) {
                return ValidationResult.failure("DUPLICATE_STEP_ID", "Duplicate stepId: " + step.stepId());
            }
            if (step.status() == TaskStep.Status.ACTIVE) {
                activeCount++;
            }
            if (step.status() == TaskStep.Status.DONE) {
                if (step.evidenceRefs().isEmpty()) {
                    return ValidationResult.failure("MISSING_EVIDENCE",
                            "Step '" + step.stepId() + "' is marked DONE but specifies no evidenceRefs");
                }
                for (String ref : step.evidenceRefs()) {
                    if (!knownEvidenceIds.contains(ref)) {
                        return ValidationResult.failure("INVALID_EVIDENCE_REF",
                                "Step '" + step.stepId() + "' references non-existent evidence or artifact: " + ref);
                    }
                }
            }
            if (step.status() == TaskStep.Status.DROPPED && (step.reason() == null || step.reason().isBlank())) {
                return ValidationResult.failure("MISSING_DROP_REASON",
                        "Step '" + step.stepId() + "' is DROPPED but no reason was provided");
            }
        }

        if (activeCount > 1) {
            return ValidationResult.failure("MULTIPLE_ACTIVE_STEPS",
                    "At most one step can be ACTIVE at any time, but found: " + activeCount);
        }

        return ValidationResult.ok();
    }

    public TaskPlan withNextRevision(String updateReason, List<TaskStep> newSteps) {
        return new TaskPlan(this.planRevision + 1, this.goalVersion, updateReason, newSteps);
    }

    /**
     * Checks if the plan execution has reached a self-consistent completed state.
     * <p>
     * Returns true if:
     * <ul>
     *   <li>The plan is non-empty;</li>
     *   <li>No step remains in {@link TaskStep.Status#PENDING}, {@link TaskStep.Status#ACTIVE}, or {@link TaskStep.Status#BLOCKED};</li>
     *   <li>At least one step was successfully completed ({@link TaskStep.Status#DONE});</li>
     *   <li>Any {@link TaskStep.Status#DROPPED} steps have valid reasons (which is enforced on update).</li>
     * </ul>
     *
     * @return true if the plan is consistently satisfied
     */
    public boolean isSatisfied() {
        if (steps.isEmpty()) {
            return false;
        }
        boolean hasDone = false;
        for (TaskStep step : steps) {
            if (step.status() == TaskStep.Status.PENDING
                    || step.status() == TaskStep.Status.ACTIVE
                    || step.status() == TaskStep.Status.BLOCKED) {
                return false;
            }
            if (step.status() == TaskStep.Status.DONE) {
                hasDone = true;
            }
        }
        return hasDone;
    }
}
