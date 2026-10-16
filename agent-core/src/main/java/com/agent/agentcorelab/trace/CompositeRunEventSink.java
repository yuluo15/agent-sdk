package com.agent.agentcorelab.trace;

import java.util.List;
import java.util.Objects;

/**
 * Composite {@link RunEventSink} that delegates events to a primary sink (e.g., file-based Jsonl sink)
 * and safely broadcasts each event to registered {@link RunEventListener} instances.
 * <p>
 * Failure isolation: exceptions thrown by listeners are suppressed/isolated so they never
 * disrupt primary persistence or agent execution.
 */
public final class CompositeRunEventSink implements RunEventSink {

    private final RunEventSink primary;
    private final List<RunEventListener> listeners;

    public CompositeRunEventSink(RunEventSink primary, List<RunEventListener> listeners) {
        this.primary = Objects.requireNonNull(primary, "primary sink cannot be null");
        this.listeners = listeners != null ? List.copyOf(listeners) : List.of();
    }

    @Override
    public void publish(RunEvent event) {
        try {
            primary.publish(event);
        } finally {
            for (RunEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (Exception ignored) {
                    // Suppress listener failures to protect core execution flow
                }
            }
        }
    }

    public RunEventSink primary() {
        return primary;
    }

    public List<RunEventListener> listeners() {
        return listeners;
    }
}
