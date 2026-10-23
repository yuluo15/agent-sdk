package com.agent.agentcorelab.trace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable enterprise audit event capturing governance decisions, actor identities,
 * tool invocations with canonical argument hashes, policy evaluations, and approval resolutions.
 * <p>
 * Privacy Redline: This event strictly records decision criteria and parameter fingerprints (SHA-256).
 * Full model prompts, user conversations, and raw sensitive content are strictly excluded.
 */
public record AuditEvent(
        UUID auditId,
        Instant timestamp,
        Action action,
        UUID runId,
        String traceId,
        String tenantId,
        String userPrincipal,
        String agentId,
        String toolName,
        String callId,
        String argumentsSha256,
        String policyDecision,
        String policyReason,
        String policyVersion,
        String requiredRole,
        String approvalId,
        String approverPrincipal,
        String approverTenantId,
        String approvalDecision,
        Map<String, String> metadata
) {
    public enum Action {
        RUN_STARTED,
        POLICY_EVALUATED,
        POLICY_DENIED,
        APPROVAL_REQUESTED,
        APPROVAL_RESOLVED,
        TOOL_STARTED,
        TOOL_EXECUTED,
        OUTPUT_REDACTED,
        OUTPUT_BLOCKED,
        RUN_FINISHED
    }

    public AuditEvent {
        auditId = auditId != null ? auditId : UUID.randomUUID();
        timestamp = timestamp != null ? timestamp : Instant.now();
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(runId, "runId cannot be null");
        traceId = traceId != null ? traceId : "";
        tenantId = tenantId != null ? tenantId : "";
        userPrincipal = userPrincipal != null ? userPrincipal : "";
        agentId = agentId != null ? agentId : "";
        toolName = toolName != null ? toolName : "";
        callId = callId != null ? callId : "";
        argumentsSha256 = argumentsSha256 != null ? argumentsSha256 : "";
        policyDecision = policyDecision != null ? policyDecision : "";
        policyReason = policyReason != null ? policyReason : "";
        policyVersion = policyVersion != null ? policyVersion : "";
        requiredRole = requiredRole != null ? requiredRole : "";
        approvalId = approvalId != null ? approvalId : "";
        approverPrincipal = approverPrincipal != null ? approverPrincipal : "";
        approverTenantId = approverTenantId != null ? approverTenantId : "";
        approvalDecision = approvalDecision != null ? approvalDecision : "";
        metadata = metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID auditId = UUID.randomUUID();
        private Instant timestamp = Instant.now();
        private Action action;
        private UUID runId;
        private String traceId = "";
        private String tenantId = "";
        private String userPrincipal = "";
        private String agentId = "";
        private String toolName = "";
        private String callId = "";
        private String argumentsSha256 = "";
        private String policyDecision = "";
        private String policyReason = "";
        private String policyVersion = "";
        private String requiredRole = "";
        private String approvalId = "";
        private String approverPrincipal = "";
        private String approverTenantId = "";
        private String approvalDecision = "";
        private final Map<String, String> metadata = new LinkedHashMap<>();

        public Builder auditId(UUID auditId) {
            this.auditId = auditId;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder runId(UUID runId) {
            this.runId = runId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId != null ? traceId : "";
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId != null ? tenantId : "";
            return this;
        }

        public Builder userPrincipal(String userPrincipal) {
            this.userPrincipal = userPrincipal != null ? userPrincipal : "";
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId != null ? agentId : "";
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName != null ? toolName : "";
            return this;
        }

        public Builder callId(String callId) {
            this.callId = callId != null ? callId : "";
            return this;
        }

        public Builder argumentsSha256(String argumentsSha256) {
            this.argumentsSha256 = argumentsSha256 != null ? argumentsSha256 : "";
            return this;
        }

        public Builder policyDecision(String policyDecision) {
            this.policyDecision = policyDecision != null ? policyDecision : "";
            return this;
        }

        public Builder policyReason(String policyReason) {
            this.policyReason = policyReason != null ? policyReason : "";
            return this;
        }

        public Builder policyVersion(String policyVersion) {
            this.policyVersion = policyVersion != null ? policyVersion : "";
            return this;
        }

        public Builder requiredRole(String requiredRole) {
            this.requiredRole = requiredRole != null ? requiredRole : "";
            return this;
        }

        public Builder approvalId(String approvalId) {
            this.approvalId = approvalId != null ? approvalId : "";
            return this;
        }

        public Builder approverPrincipal(String approverPrincipal) {
            this.approverPrincipal = approverPrincipal != null ? approverPrincipal : "";
            return this;
        }

        public Builder approverTenantId(String approverTenantId) {
            this.approverTenantId = approverTenantId != null ? approverTenantId : "";
            return this;
        }

        public Builder approvalDecision(String approvalDecision) {
            this.approvalDecision = approvalDecision != null ? approvalDecision : "";
            return this;
        }

        public Builder metadata(String key, String value) {
            if (key != null && value != null) {
                this.metadata.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public AuditEvent build() {
            return new AuditEvent(
                    auditId, timestamp, action, runId, traceId, tenantId, userPrincipal,
                    agentId, toolName, callId, argumentsSha256, policyDecision,
                    policyReason, policyVersion, requiredRole, approvalId,
                    approverPrincipal, approverTenantId, approvalDecision, metadata
            );
        }
    }
}
