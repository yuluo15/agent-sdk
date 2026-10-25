package com.agent.agentcorelab.checkpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class NoopCheckpointStore implements CheckpointSpi {
    static final NoopCheckpointStore INSTANCE = new NoopCheckpointStore();

    private NoopCheckpointStore() {
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
    }

    @Override
    public Optional<RunCheckpoint> findByRunId(UUID runId) {
        return Optional.empty();
    }

    @Override
    public void remove(UUID runId) {
    }

    @Override
    public List<RunCheckpoint> findWaiting() {
        return List.of();
    }
}
