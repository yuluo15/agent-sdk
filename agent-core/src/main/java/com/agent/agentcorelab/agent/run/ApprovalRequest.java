package com.agent.agentcorelab.agent.run;

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
        Instant requestedAt,
        String policyVersion,
        String requiredRole
) {
    public ApprovalRequest {
        Objects.requireNonNull(approvalId, "approvalId cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(argumentsJson, "argumentsJson cannot be null");
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
        policyVersion = policyVersion != null && !policyVersion.isBlank() ? policyVersion : "1.0";
        requiredRole = requiredRole != null && !requiredRole.isBlank() ? requiredRole : "OPERATOR";
    }

    public ApprovalRequest(
            String approvalId,
            String callId,
            String toolName,
            String argumentsJson,
            String description,
            Instant requestedAt
    ) {
        this(approvalId, callId, toolName, argumentsJson, description, requestedAt, "1.0", "OPERATOR");
    }

    public static ApprovalRequest of(String approvalId, String callId, String toolName, String argumentsJson, String description) {
        return new ApprovalRequest(approvalId, callId, toolName, argumentsJson, description, Instant.now());
    }

    public static ApprovalRequest of(
            String approvalId,
            String callId,
            String toolName,
            String argumentsJson,
            String description,
            String policyVersion,
            String requiredRole
    ) {
        return new ApprovalRequest(approvalId, callId, toolName, argumentsJson, description, Instant.now(), policyVersion, requiredRole);
    }
}
