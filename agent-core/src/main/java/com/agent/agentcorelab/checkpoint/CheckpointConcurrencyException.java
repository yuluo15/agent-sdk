package com.agent.agentcorelab.checkpoint;

import java.util.UUID;

/**
 * Thrown when an optimistic concurrency version conflict occurs during checkpoint persistence.
 */
public class CheckpointConcurrencyException extends RuntimeException {
    private final UUID runId;
    private final long currentVersion;
    private final long attemptedVersion;

    public CheckpointConcurrencyException(UUID runId, long currentVersion, long attemptedVersion) {
        super("Optimistic locking conflict on checkpoint for runId '" + runId + "': current version is "
                + currentVersion + ", but attempted to update with version " + attemptedVersion);
        this.runId = runId;
        this.currentVersion = currentVersion;
        this.attemptedVersion = attemptedVersion;
    }

    public CheckpointConcurrencyException(String message) {
        super(message);
        this.runId = null;
        this.currentVersion = -1;
        this.attemptedVersion = -1;
    }

    public UUID runId() {
        return runId;
    }

    public long currentVersion() {
        return currentVersion;
    }

    public long attemptedVersion() {
        return attemptedVersion;
    }
}
