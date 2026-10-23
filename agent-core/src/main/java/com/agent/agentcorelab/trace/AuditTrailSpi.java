package com.agent.agentcorelab.trace;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Append-Only Enterprise Audit Trail Service Provider Interface (SPI).
 * <p>
 * Strictly enforces an immutable, append-only contract for recording security governance decisions,
 * high-risk tool authorizations, policy enforcements, and approval outcomes.
 * Modification or deletion of past audit records is strictly prohibited at the SDK level.
 */
@FunctionalInterface
public interface AuditTrailSpi {

    /**
     * Appends an immutable audit event to the enterprise audit trail.
     *
     * @param event the audit event capturing governance decisions, actor identity, and parameter hashes
     */
    void append(AuditEvent event);

    /**
     * No-op implementation for environments where external enterprise audit trailing is disabled.
     */
    static AuditTrailSpi noop() {
        return event -> {};
    }

    /**
     * In-memory append-only audit trail provider, ideal for unit testing and local verification.
     */
    static InMemoryAuditTrail inMemory() {
        return new InMemoryAuditTrail();
    }

    /**
     * Creates a composite audit trail that dispatches each audit event to multiple underlying SPIs.
     * <p>
     * Failure isolation: exceptions thrown by any individual SPI are safely suppressed to prevent
     * disruption of primary execution flow.
     */
    static AuditTrailSpi composite(AuditTrailSpi... trails) {
        if (trails == null || trails.length == 0) {
            return noop();
        }
        List<AuditTrailSpi> list = Arrays.stream(trails)
                .filter(Objects::nonNull)
                .toList();
        if (list.isEmpty()) {
            return noop();
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        return event -> {
            for (AuditTrailSpi trail : list) {
                try {
                    trail.append(event);
                } catch (Exception ignored) {
                    // Suppress failure in secondary audit sinks to protect core execution flow
                }
            }
        };
    }
}
