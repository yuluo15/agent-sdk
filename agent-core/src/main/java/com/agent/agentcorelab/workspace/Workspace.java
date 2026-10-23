package com.agent.agentcorelab.workspace;

import java.nio.file.Path;
import java.util.Objects;

// Trusted configuration only; WorkspaceSession validates real filesystem boundaries per run.
public record Workspace(Path inputRoot, Path outputRoot, Path traceRoot, boolean createInputIfMissing) {
    public Workspace(Path inputRoot, Path outputRoot, Path traceRoot) {
        this(inputRoot, outputRoot, traceRoot, false);
    }

    public Workspace {
        Objects.requireNonNull(inputRoot, "inputRoot");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(traceRoot, "traceRoot");
    }
}
