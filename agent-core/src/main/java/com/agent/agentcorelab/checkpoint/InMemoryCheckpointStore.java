package com.agent.agentcorelab.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory implementation of {@link CheckpointSpi} with optimistic locking concurrency control.
 */
public final class InMemoryCheckpointStore implements CheckpointSpi {

    private final ConcurrentMap<UUID, RunCheckpoint> storage = new ConcurrentHashMap<>();

    @Override
    public void save(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        storage.compute(checkpoint.runId(), (id, existing) -> {
            if (existing != null) {
                if (checkpoint.version() <= existing.version()) {
                    throw new CheckpointConcurrencyException(checkpoint.runId(), existing.version(), checkpoint.version());
                }
            }
            return checkpoint;
        });
    }

    @Override
    public Optional<RunCheckpoint> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(storage.get(runId));
    }

    @Override
    public void remove(UUID runId) {
        if (runId != null) {
            storage.remove(runId);
        }
    }

    @Override
    public List<RunCheckpoint> findWaiting() {
        return new ArrayList<>(storage.values());
    }

    public int size() {
        return storage.size();
    }

    public void clear() {
        storage.clear();
    }
}
