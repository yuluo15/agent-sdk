package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceSession;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public record ToolContext(
        UUID runId,
        Workspace workspace,
        Instant deadline,
        BooleanSupplier cancellationRequested,
        Consumer<Artifact> artifactSink,
        WorkspaceSession resources
) {
    public void checkActive() {
        if (cancellationRequested.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException("Run cancelled");
        }
        if (!Instant.now().isBefore(deadline)) {
            throw new ActiveTimeExceededException();
        }
    }
}
