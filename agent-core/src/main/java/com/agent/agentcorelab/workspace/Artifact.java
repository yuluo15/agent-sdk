package com.agent.agentcorelab.workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record Artifact(String id, Path path, String contentDigest, List<String> evidenceIds, int goalVersion) {
    public Artifact(String id, Path path, String contentDigest, List<String> evidenceIds) {
        this(id, path, contentDigest, evidenceIds, 1);
    }

    public Artifact {
        if (id == null || id.isBlank() || contentDigest == null || contentDigest.isBlank()) {
            throw new IllegalArgumentException("Artifact identity and content digest are required");
        }
        Objects.requireNonNull(path, "path");
        evidenceIds = List.copyOf(evidenceIds != null ? evidenceIds : List.of());
        if (goalVersion <= 0) {
            goalVersion = 1;
        }
    }
}
