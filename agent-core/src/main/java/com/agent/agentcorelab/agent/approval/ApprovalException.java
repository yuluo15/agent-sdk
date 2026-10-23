package com.agent.agentcorelab.agent.approval;

import java.util.Objects;

/**
 * Exception thrown when approval validation fails (e.g. parameter tampering, replay, expiration, or unauthorized approver).
 */
public class ApprovalException extends IllegalStateException {

    private final String errorCode;

    public ApprovalException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode cannot be null");
    }

    public String errorCode() {
        return errorCode;
    }
}
