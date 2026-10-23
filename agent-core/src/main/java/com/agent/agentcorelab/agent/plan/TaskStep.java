package com.agent.agentcorelab.agent.plan;

import java.util.List;
import java.util.Objects;

/**
 * An individual planned step within a TaskPlan.
 */
public record TaskStep(
        String stepId,
        String title,
        String description,
        Status status,
        List<String> evidenceRefs,
        String reason
) {
    public enum Status {
        PENDING,
        ACTIVE,
        DONE,
        BLOCKED,
        DROPPED
    }

    public TaskStep {
        if (stepId == null || !stepId.matches("[a-zA-Z0-9_-]{1,50}")) {
            throw new IllegalArgumentException("stepId must match [a-zA-Z0-9_-]{1,50}, got: " + stepId);
        }
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title must be between 1 and 200 characters");
        }
        description = description != null ? description : "";
        Objects.requireNonNull(status, "status");
        evidenceRefs = List.copyOf(evidenceRefs != null ? evidenceRefs : List.of());
        reason = reason != null ? reason : "";
    }

    public static TaskStep of(String stepId, String title) {
        return new TaskStep(stepId, title, "", Status.PENDING, List.of(), "");
    }

    public static TaskStep of(String stepId, String title, Status status) {
        return new TaskStep(stepId, title, "", status, List.of(), "");
    }

    public static TaskStep of(String stepId, String title, Status status, List<String> evidenceRefs, String reason) {
        return new TaskStep(stepId, title, "", status, evidenceRefs, reason);
    }
}
