package com.agent.agentcorelab.agent.run;

import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.plan.TaskBrief;
import com.agent.agentcorelab.workspace.Workspace;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

public record RunRequest(
        String goal,
        Workspace workspace,
        BooleanSupplier cancellationRequested,
        Optional<TaskBrief> initialBrief,
        Optional<String> conversationId,
        Optional<String> userId,
        Optional<TrustedContext> trustedContext
) {

    public RunRequest(
            String goal,
            Workspace workspace,
            BooleanSupplier cancellationRequested,
            Optional<TaskBrief> initialBrief,
            Optional<String> conversationId,
            Optional<String> userId
    ) {
        this(goal, workspace, cancellationRequested, initialBrief, conversationId, userId, Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace) {
        this(goal, workspace, () -> false, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, String conversationId) {
        this(goal, workspace, () -> false, Optional.empty(), Optional.ofNullable(conversationId), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, String conversationId, String userId) {
        this(goal, workspace, () -> false, Optional.empty(), Optional.ofNullable(conversationId), Optional.ofNullable(userId));
    }

    public RunRequest(String goal, Workspace workspace, BooleanSupplier cancellationRequested) {
        this(goal, workspace, cancellationRequested, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, TaskBrief initialBrief) {
        this(goal, workspace, () -> false, Optional.ofNullable(initialBrief), Optional.empty(), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, TaskBrief initialBrief, String conversationId) {
        this(goal, workspace, () -> false, Optional.ofNullable(initialBrief), Optional.ofNullable(conversationId), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, TaskBrief initialBrief, String conversationId, String userId) {
        this(goal, workspace, () -> false, Optional.ofNullable(initialBrief), Optional.ofNullable(conversationId), Optional.ofNullable(userId));
    }

    public RunRequest(String goal, Workspace workspace, BooleanSupplier cancellationRequested, Optional<TaskBrief> initialBrief) {
        this(goal, workspace, cancellationRequested, initialBrief, Optional.empty(), Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, BooleanSupplier cancellationRequested, Optional<TaskBrief> initialBrief, Optional<String> conversationId) {
        this(goal, workspace, cancellationRequested, initialBrief, conversationId, Optional.empty());
    }

    public RunRequest(String goal, Workspace workspace, TrustedContext trustedContext) {
        this(goal, workspace, () -> false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(trustedContext));
    }

    public RunRequest(String goal, Workspace workspace, TaskBrief initialBrief, TrustedContext trustedContext) {
        this(goal, workspace, () -> false, Optional.ofNullable(initialBrief), Optional.empty(), Optional.empty(), Optional.ofNullable(trustedContext));
    }

    public RunRequest {
        if (goal == null || goal.isBlank() || goal.length() > 16000) {
            throw new IllegalArgumentException("Goal must contain between 1 and 16000 characters");
        }
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(cancellationRequested, "cancellationRequested");
        initialBrief = initialBrief != null ? initialBrief : Optional.empty();
        conversationId = conversationId != null ? conversationId : Optional.empty();
        userId = userId != null ? userId : Optional.empty();
        trustedContext = trustedContext != null ? trustedContext : Optional.empty();
    }

    public RunRequest withTrustedContext(TrustedContext context) {
        return new RunRequest(goal, workspace, cancellationRequested, initialBrief, conversationId, userId, Optional.ofNullable(context));
    }

    public TrustedContext resolvedTrustedContext() {
        return trustedContext.orElseGet(() -> new TrustedContext(
                TrustedContext.DEFAULT_TENANT,
                userId.orElse(TrustedContext.ANONYMOUS_USER),
                TrustedContext.DEFAULT_AGENT,
                Set.of(),
                conversationId.orElse(null),
                null
        ));
    }
}
