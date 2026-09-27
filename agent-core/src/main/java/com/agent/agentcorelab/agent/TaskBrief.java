package com.agent.agentcorelab.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot of the current goal, constraints, required deliverables,
 * and unresolved issues for a run, decoupled from raw chat history.
 */
public record TaskBrief(
        int goalVersion,
        String objective,
        Map<String, String> constraints,
        List<String> deliverables,
        List<String> unresolved
) {
    public TaskBrief {
        if (goalVersion <= 0) {
            throw new IllegalArgumentException("goalVersion must be positive, got: " + goalVersion);
        }
        if (objective == null || objective.isBlank() || objective.length() > 16000) {
            throw new IllegalArgumentException("objective must contain between 1 and 16000 characters");
        }
        constraints = Collections.unmodifiableMap(new LinkedHashMap<>(constraints != null ? constraints : Map.of()));
        deliverables = List.copyOf(deliverables != null ? deliverables : List.of());
        unresolved = List.copyOf(unresolved != null ? unresolved : List.of());
    }

    public static TaskBrief of(String objective) {
        return new TaskBrief(1, objective, Map.of(), List.of(), List.of());
    }

    public static TaskBrief of(String objective, List<String> deliverables) {
        return new TaskBrief(1, objective, Map.of(), deliverables, List.of());
    }

    public static TaskBrief of(String objective, Map<String, String> constraints, List<String> deliverables) {
        return new TaskBrief(1, objective, constraints, deliverables, List.of());
    }

    public TaskBrief withRevisedGoal(String newObjective) {
        return new TaskBrief(this.goalVersion + 1, newObjective, this.constraints, this.deliverables, this.unresolved);
    }

    public TaskBrief withRevisedGoal(String newObjective, Map<String, String> newConstraints, List<String> newDeliverables) {
        return new TaskBrief(this.goalVersion + 1, newObjective, newConstraints, newDeliverables, this.unresolved);
    }

    public TaskBrief withConstraints(Map<String, String> newConstraints) {
        return new TaskBrief(this.goalVersion, this.objective, newConstraints, this.deliverables, this.unresolved);
    }

    public TaskBrief withDeliverables(List<String> newDeliverables) {
        return new TaskBrief(this.goalVersion, this.objective, this.constraints, newDeliverables, this.unresolved);
    }

    public TaskBrief withUnresolved(List<String> newUnresolved) {
        return new TaskBrief(this.goalVersion, this.objective, this.constraints, this.deliverables, newUnresolved);
    }

    /**
     * Formats the task brief into a standardized structured context block
     * to be injected into model prompts or displayed in transcripts.
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("[TaskBrief v").append(goalVersion).append("]\n");
        sb.append("Objective: ").append(objective).append("\n");
        if (!constraints.isEmpty()) {
            sb.append("Constraints:\n");
            constraints.forEach((k, v) -> sb.append("  - ").append(k).append(": ").append(v).append("\n"));
        }
        if (!deliverables.isEmpty()) {
            sb.append("Required Deliverables:\n");
            deliverables.forEach(d -> sb.append("  - ").append(d).append("\n"));
        }
        if (!unresolved.isEmpty()) {
            sb.append("Unresolved Issues:\n");
            unresolved.forEach(u -> sb.append("  - ").append(u).append("\n"));
        }
        return sb.toString().stripTrailing();
    }
}
