package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.workspace.Workspace;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public record RunRequest(
        String goal,
        Workspace workspace,
        BooleanSupplier cancellationRequested,
        Optional<TaskBrief> initialBrief
) {
    public RunRequest(String goal, Workspace workspace) {
        this(goal, workspace, () -> false, Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, BooleanSupplier cancellationRequested) {
        this(goal, workspace, cancellationRequested, Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, TaskBrief initialBrief) {
        this(goal, workspace, () -> false, Optional.ofNullable(initialBrief));
    }

    public RunRequest {
        if (goal == null || goal.isBlank() || goal.length() > 16000) {
            throw new IllegalArgumentException("Goal must contain between 1 and 16000 characters");
        }
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        initialBrief = initialBrief != null ? initialBrief : Optional.empty();
    }
}
