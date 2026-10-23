package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceSession;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Execution context provided to an Agent {@link Tool} invocation.
 * <p>
 * Aggregates runtime deadline constraints, cancellation signaling, artifact output channel,
 * temporary session resources, and host-injected immutable {@link TrustedContext}.
 */
public record ToolContext(
        UUID runId,
        Workspace workspace,
        Instant deadline,
        BooleanSupplier cancellationRequested,
        Consumer<Artifact> artifactSink,
        WorkspaceSession resources,
        TrustedContext trustedContext
) {

    public ToolContext(
            UUID runId,
            Workspace workspace,
            Instant deadline,
            BooleanSupplier cancellationRequested,
            Consumer<Artifact> artifactSink,
            WorkspaceSession resources
    ) {
        this(runId, workspace, deadline, cancellationRequested, artifactSink, resources, TrustedContext.anonymous());
    }

    public ToolContext {
        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(deadline, "deadline cannot be null");
        cancellationRequested = cancellationRequested != null ? cancellationRequested : () -> false;
        artifactSink = artifactSink != null ? artifactSink : a -> {};
        trustedContext = trustedContext != null ? trustedContext : TrustedContext.anonymous();
    }

    public void checkActive() {
        if (cancellationRequested.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("Run cancelled");
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new ActiveTimeExceededException();
        }
    }

    public String tenantId() {
        return trustedContext.tenantId();
    }

    public String userPrincipal() {
        return trustedContext.userPrincipal();
    }

    public boolean hasScope(String scope) {
        return trustedContext.hasScope(scope);
    }
}
