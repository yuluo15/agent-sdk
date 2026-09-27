package com.agent.agentcorelab.agent;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record representing a pending human approval request for a high-risk tool action.
 */
public record ApprovalRequest(
        String approvalId,
        String callId,
        String toolName,
        String argumentsJson,
        String description,
        Instant requestedAt
) {
    public ApprovalRequest {
        Objects.requireNonNull(approvalId, "approvalId cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(argumentsJson, "argumentsJson cannot be null");
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
    }

    public static ApprovalRequest of(String approvalId, String callId, String toolName, String argumentsJson, String description) {
        return new ApprovalRequest(approvalId, callId, toolName, argumentsJson, description, Instant.now());
    }
}
