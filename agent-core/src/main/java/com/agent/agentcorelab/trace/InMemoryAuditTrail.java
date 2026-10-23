package com.agent.agentcorelab.trace;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link AuditTrailSpi} strictly adhering to the append-only contract.
 * <p>
 * Provides thread-safe append operations and read-only query capabilities for testing and local verification.
 * Exposes NO modification, update, or deletion operations.
 */
public final class InMemoryAuditTrail implements AuditTrailSpi {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditEvent event) {
        Objects.requireNonNull(event, "AuditEvent cannot be null");
        events.add(event);
    }

    /**
     * Returns an unmodifiable snapshot view of all recorded audit events in chronological order.
     */
    public List<AuditEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Returns the total count of recorded audit events.
     */
    public int size() {
        return events.size();
    }

    /**
     * Checks if the audit trail is empty.
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Finds audit events matching a specific action.
     */
    public List<AuditEvent> findByAction(AuditEvent.Action action) {
        if (action == null) {
            return List.of();
        }
        return events.stream().filter(e -> e.action() == action).toList();
    }

    /**
     * Finds audit events for a specific tool name.
     */
    public List<AuditEvent> findByTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return List.of();
        }
        return events.stream().filter(e -> toolName.equalsIgnoreCase(e.toolName())).toList();
    }
}
