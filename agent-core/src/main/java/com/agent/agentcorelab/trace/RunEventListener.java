package com.agent.agentcorelab.trace;

/**
 * Listener interface for observing lifecycle and execution events emitted during an agent run.
 * <p>
 * Implementations can be used for metrics export (e.g., Micrometer), distributed tracing,
 * audit logging, or external notification without modifying the core execution engine.
 */
@FunctionalInterface
public interface RunEventListener {

    /**
     * Invoked when a run event occurs.
     *
     * @param event the emitted event containing sequence, runId, timestamp, type, and payload summary
     */
    void onEvent(RunEvent event);
}
