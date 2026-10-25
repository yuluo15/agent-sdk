package com.agent.agentcorelab.checkpoint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service Provider Interface (SPI) for persisting resting-state checkpoints.
 * <p>
 * Supported states: strictly {@code WAITING_INPUT} and {@code WAITING_APPROVAL}.
 * Supports optimistic locking version control to prevent lost updates or split-brain writes.
 */
public interface CheckpointSpi {

    /**
     * Saves or updates a run checkpoint snapshot.
     *
     * @param checkpoint the checkpoint snapshot
     * @throws CheckpointConcurrencyException if a concurrent update version conflict occurs
     */
    void save(RunCheckpoint checkpoint);

    /**
     * Retrieves the latest checkpoint for the specified runId.
     *
     * @param runId the run identifier
     * @return the checkpoint if present
     */
    Optional<RunCheckpoint> findByRunId(UUID runId);

    /**
     * Removes the checkpoint when the run terminates (reaches COMPLETED, FAILED, CANCELLED, or STOPPED).
     *
     * @param runId the run identifier
     */
    void remove(UUID runId);

    /**
     * Lists all active pending checkpoints currently persisted in storage.
     *
     * @return list of pending checkpoints
     */
    default List<RunCheckpoint> findWaiting() {
        return List.of();
    }

    /**
     * Returns a no-op checkpoint SPI implementation.
     */
    static CheckpointSpi noop() {
        return NoopCheckpointStore.INSTANCE;
    }

    /**
     * Returns a thread-safe in-memory checkpoint SPI implementation with optimistic locking.
     */
    static CheckpointSpi inMemory() {
        return new InMemoryCheckpointStore();
    }
}
