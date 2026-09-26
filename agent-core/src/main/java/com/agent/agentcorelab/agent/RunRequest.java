package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.workspace.Workspace;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record RunRequest(String goal, Workspace workspace, BooleanSupplier cancellationRequested) {
    public RunRequest(String goal, Workspace workspace) {
        this(goal, workspace, () -> false);
    }

    public RunRequest {
        if (goal == null || goal.isBlank() || goal.length() > 16000) {
            throw new IllegalArgumentException("Goal must contain between 1 and 16000 characters");
        }
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
    }
}
