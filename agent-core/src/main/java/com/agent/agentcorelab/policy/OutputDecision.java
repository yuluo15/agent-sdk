package com.agent.agentcorelab.policy;

import com.agent.agentcorelab.tool.ToolOutcome;

import java.util.Objects;

/**
 * Post-execution egress protection decision produced by {@link ToolPolicy#afterExecute}.
 */
public sealed interface OutputDecision {

    enum DecisionType {
        ALLOW_OUTPUT,
        REDACT,
        DENY_OUTPUT
    }

    DecisionType type();

    record AllowOutput() implements OutputDecision {
        @Override
        public DecisionType type() {
            return DecisionType.ALLOW_OUTPUT;
        }
    }

    record Redact(ToolOutcome sanitizedOutcome, String reason) implements OutputDecision {
        public Redact {
            Objects.requireNonNull(sanitizedOutcome, "sanitizedOutcome cannot be null");
            reason = reason != null ? reason : "Sanitized by egress policy";
        }

        public Redact(ToolOutcome sanitizedOutcome) {
            this(sanitizedOutcome, "Sanitized by egress policy");
        }

        @Override
        public DecisionType type() {
            return DecisionType.REDACT;
        }
    }

    record DenyOutput(String reason, String errorCode) implements OutputDecision {
        public DenyOutput {
            reason = reason != null ? reason : "Output blocked by egress policy";
            errorCode = errorCode != null && !errorCode.isBlank() ? errorCode : "OUTPUT_BLOCKED";
        }

        public DenyOutput(String reason) {
            this(reason, "OUTPUT_BLOCKED");
        }

        @Override
        public DecisionType type() {
            return DecisionType.DENY_OUTPUT;
        }
    }

    static AllowOutput allow() {
        return new AllowOutput();
    }

    static Redact redact(ToolOutcome sanitizedOutcome) {
        return new Redact(sanitizedOutcome);
    }

    static Redact redact(ToolOutcome sanitizedOutcome, String reason) {
        return new Redact(sanitizedOutcome, reason);
    }

    static DenyOutput deny(String reason) {
        return new DenyOutput(reason);
    }

    static DenyOutput deny(String reason, String errorCode) {
        return new DenyOutput(reason, errorCode);
    }
}
