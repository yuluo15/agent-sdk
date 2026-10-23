package com.agent.agentcorelab.agent.approval;

import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.tool.ToolJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable security binding tying a pending high-risk approval to requester identity,
 * tenant boundary, required role, canonical parameter digest, and single-use lifecycle.
 */
public record ApprovalBinding(
        UUID approvalId,
        String tenantId,
        String requesterPrincipal,
        String requiredRole,
        String toolName,
        String callId,
        String argumentsSha256,
        String policyVersion,
        Instant expiresAt,
        AtomicBoolean consumed
) {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    public ApprovalBinding {
        Objects.requireNonNull(approvalId, "approvalId cannot be null");
        tenantId = tenantId != null ? tenantId : "";
        requesterPrincipal = requesterPrincipal != null ? requesterPrincipal : "";
        requiredRole = requiredRole != null ? requiredRole : "OPERATOR";
        Objects.requireNonNull(toolName, "toolName cannot be null");
        Objects.requireNonNull(callId, "callId cannot be null");
        Objects.requireNonNull(argumentsSha256, "argumentsSha256 cannot be null");
        policyVersion = policyVersion != null ? policyVersion : "1.0";
        expiresAt = expiresAt != null ? expiresAt : Instant.now().plus(DEFAULT_TTL);
        consumed = consumed != null ? consumed : new AtomicBoolean(false);
    }

    public ApprovalBinding(
            UUID approvalId,
            String tenantId,
            String requesterPrincipal,
            String requiredRole,
            String toolName,
            String callId,
            String argumentsSha256,
            String policyVersion,
            Instant expiresAt
    ) {
        this(approvalId, tenantId, requesterPrincipal, requiredRole, toolName, callId,
                argumentsSha256, policyVersion, expiresAt, new AtomicBoolean(false));
    }

    public boolean isExpired(Instant now) {
        return (now != null ? now : Instant.now()).isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumed.get();
    }

    public boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }

    /**
     * Validates this approval attempt against security invariants:
     * 1. Anti-Replay: check already consumed
     * 2. Expiration: expiresAt TTL check
     * 3. Anti-Tamper: canonical SHA-256 argument hash check
     * 4. Authorization: approver tenant isolation and requiredRole scope check
     * 5. Anti-Replay: atomic single-use CAS consumption
     */
    public void validateAndConsume(String actualArgumentsJson, TrustedContext approverContext) {
        // 1. Anti-Replay check on already consumed token
        if (isConsumed()) {
            throw new ApprovalException("APPROVAL_REPLAY",
                    "Approval token '" + approvalId + "' has already been consumed (replay attempt detected)");
        }

        // 2. Expiration check
        if (isExpired(Instant.now())) {
            throw new ApprovalException("APPROVAL_EXPIRED",
                    "Approval token '" + approvalId + "' expired at " + expiresAt);
        }

        // 3. Anti-Tamper check
        String currentHash = computeArgumentsDigest(actualArgumentsJson);
        if (!this.argumentsSha256.equalsIgnoreCase(currentHash)) {
            throw new ApprovalException("APPROVAL_TAMPERED",
                    "Tool call arguments have been tampered with. Expected SHA-256: " + this.argumentsSha256 + ", actual SHA-256: " + currentHash);
        }

        // 4. Approver tenant & role check
        if (approverContext != null) {
            validateApproverAuthorization(approverContext);
        }

        // 5. Atomic single-use CAS consumption
        if (!tryConsume()) {
            throw new ApprovalException("APPROVAL_REPLAY",
                    "Approval token '" + approvalId + "' has already been consumed (replay attempt detected)");
        }
    }

    /**
     * Validates a rejection attempt and marks the token as consumed so it cannot be reused.
     */
    public void validateRejectAndConsume(TrustedContext approverContext) {
        if (isConsumed()) {
            throw new ApprovalException("APPROVAL_REPLAY",
                    "Approval token '" + approvalId + "' has already been consumed (replay attempt detected)");
        }

        if (isExpired(Instant.now())) {
            throw new ApprovalException("APPROVAL_EXPIRED",
                    "Approval token '" + approvalId + "' expired at " + expiresAt);
        }

        if (approverContext != null) {
            validateApproverAuthorization(approverContext);
        }

        if (!tryConsume()) {
            throw new ApprovalException("APPROVAL_REPLAY",
                    "Approval token '" + approvalId + "' has already been consumed (replay attempt detected)");
        }
    }

    private void validateApproverAuthorization(TrustedContext approverContext) {
        // Tenant boundary check
        if (!this.tenantId.isBlank()) {
            if (!this.tenantId.equalsIgnoreCase(approverContext.tenantId())) {
                throw new ApprovalException("APPROVAL_TENANT_MISMATCH",
                        "Approver tenant '" + approverContext.tenantId() + "' does not match approval binding tenant '" + this.tenantId + "'");
            }
        }

        // Role authorization check
        if (!this.requiredRole.isBlank()) {
            boolean authorized = approverContext.hasScope(this.requiredRole)
                    || approverContext.hasScope("*")
                    || this.requiredRole.equalsIgnoreCase(approverContext.userPrincipal());
            if (!authorized) {
                throw new ApprovalException("APPROVAL_UNAUTHORIZED_ROLE",
                        "Approver '" + approverContext.userPrincipal() + "' lacks required role '" + this.requiredRole + "'");
            }
        }
    }

    /**
     * Computes the canonical SHA-256 digest of arguments JSON with recursively sorted keys.
     */
    public static String computeArgumentsDigest(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return sha256Hex("{}".getBytes(StandardCharsets.UTF_8));
        }
        try {
            JsonNode node = ToolJson.parse(argumentsJson);
            String canonical = ToolJson.canonical(node);
            return sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return sha256Hex(argumentsJson.trim().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM must support SHA-256", e);
        }
    }
}
