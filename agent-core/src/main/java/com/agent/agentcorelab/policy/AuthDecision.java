package com.agent.agentcorelab.policy;

/**
 * Pre-execution authorization decision produced by {@link ToolPolicy#beforeExecute}.
 */
public sealed interface AuthDecision {

    enum DecisionType {
        ALLOW,
        DENY,
        REQUIRE_APPROVAL
    }

    DecisionType type();

    record Allow() implements AuthDecision {
        @Override
        public DecisionType type() {
            return DecisionType.ALLOW;
        }
    }

    record Deny(String reason, String errorCode, boolean terminal) implements AuthDecision {
        public Deny {
            reason = reason != null ? reason : "Execution denied by tool policy";
            errorCode = errorCode != null && !errorCode.isBlank() ? errorCode : "POLICY_DENIED";
        }

        public Deny(String reason) {
            this(reason, "POLICY_DENIED", false);
        }

        public Deny(String reason, String errorCode) {
            this(reason, errorCode, false);
        }

        @Override
        public DecisionType type() {
            return DecisionType.DENY;
        }
    }

    record RequireApproval(String policyVersion, String requiredRole, String reason) implements AuthDecision {
        public RequireApproval {
            policyVersion = policyVersion != null && !policyVersion.isBlank() ? policyVersion : "1.0";
            requiredRole = requiredRole != null && !requiredRole.isBlank() ? requiredRole : "OPERATOR";
            reason = reason != null ? reason : "High-risk tool call requires authorization";
        }

        public RequireApproval(String policyVersion, String requiredRole) {
            this(policyVersion, requiredRole, "High-risk tool call requires authorization");
        }

        @Override
        public DecisionType type() {
            return DecisionType.REQUIRE_APPROVAL;
        }
    }

    static Allow allow() {
        return new Allow();
    }

    static Deny deny(String reason) {
        return new Deny(reason);
    }

    static Deny deny(String reason, String errorCode) {
        return new Deny(reason, errorCode);
    }

    static Deny deny(String reason, String errorCode, boolean terminal) {
        return new Deny(reason, errorCode, terminal);
    }

    static Deny terminalDeny(String reason, String errorCode) {
        return new Deny(reason, errorCode, true);
    }

    static RequireApproval requireApproval(String policyVersion, String requiredRole) {
        return new RequireApproval(policyVersion, requiredRole);
    }

    static RequireApproval requireApproval(String policyVersion, String requiredRole, String reason) {
        return new RequireApproval(policyVersion, requiredRole, reason);
    }
}
