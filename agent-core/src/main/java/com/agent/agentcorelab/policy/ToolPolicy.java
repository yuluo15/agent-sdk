package com.agent.agentcorelab.policy;

import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Two-stage governance policy engine SPI controlling tool execution authorization and output egress.
 * <p>
 * Invoked during execution:
 * <ol>
 *   <li>{@link #beforeExecute}: evaluates whether the tool call is permitted, denied, or requires human approval;</li>
 *   <li>{@link #afterExecute}: evaluates whether the tool execution result is safe, needs redaction, or must be blocked.</li>
 * </ol>
 */
@FunctionalInterface
public interface ToolPolicy {

    /**
     * Pre-execution evaluation: determines whether the call is permitted, denied, or requires approval.
     *
     * @param tool          definition and governance semantics of the invoked tool
     * @param argumentsJson serialized JSON arguments supplied by the model
     * @param context       immutable trusted security context injected by the host
     * @return authorization decision (ALLOW, DENY, or REQUIRE_APPROVAL)
     */
    AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context);

    /**
     * Post-execution evaluation: determines whether the output is safe, needs redaction, or must be blocked.
     *
     * @param tool    definition and governance semantics of the invoked tool
     * @param outcome execution result produced by the tool
     * @param context immutable trusted security context injected by the host
     * @return output decision (ALLOW_OUTPUT, REDACT, or DENY_OUTPUT)
     */
    default OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
        return OutputDecision.allow();
    }

    /**
     * Default policy preserving backward compatibility:
     * - Tools marked {@link ToolDefinition#isHighRisk()} trigger {@link AuthDecision.RequireApproval};
     * - All other tools are allowed;
     * - All outputs are allowed unmodified.
     */
    static ToolPolicy defaultPolicy() {
        return new DefaultToolPolicy();
    }

    /**
     * Permissive policy allowing all executions and outputs without approval.
     */
    static ToolPolicy permissive() {
        return new PermissiveToolPolicy();
    }

    /**
     * Creates a composite policy evaluating multiple policies in order.
     */
    static ToolPolicy composite(ToolPolicy... policies) {
        return new CompositeToolPolicy(List.of(policies));
    }

    /**
     * Creates a composite policy evaluating multiple policies in order.
     */
    static ToolPolicy composite(List<ToolPolicy> policies) {
        return new CompositeToolPolicy(policies);
    }

    static Builder builder() {
        return new Builder();
    }

    class Builder {
        private final Map<String, AuthDecision> toolDecisions = new HashMap<>();
        private boolean requireApprovalForHighRisk = true;
        private ToolPolicy beforeInterceptor;
        private PostExecutionHandler afterInterceptor;

        @FunctionalInterface
        public interface PostExecutionHandler {
            OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context);
        }

        public Builder requireApprovalForHighRisk(boolean require) {
            this.requireApprovalForHighRisk = require;
            return this;
        }

        public Builder denyTool(String toolName, String reason) {
            this.toolDecisions.put(Objects.requireNonNull(toolName, "toolName"), AuthDecision.deny(reason));
            return this;
        }

        public Builder denyTool(String toolName, String reason, String errorCode) {
            this.toolDecisions.put(Objects.requireNonNull(toolName, "toolName"), AuthDecision.deny(reason, errorCode));
            return this;
        }

        public Builder requireApproval(String toolName, String policyVersion, String requiredRole) {
            this.toolDecisions.put(Objects.requireNonNull(toolName, "toolName"),
                    AuthDecision.requireApproval(policyVersion, requiredRole));
            return this;
        }

        public Builder requireApproval(String toolName, String policyVersion, String requiredRole, String reason) {
            this.toolDecisions.put(Objects.requireNonNull(toolName, "toolName"),
                    AuthDecision.requireApproval(policyVersion, requiredRole, reason));
            return this;
        }

        public Builder onBeforeExecute(ToolPolicy interceptor) {
            this.beforeInterceptor = interceptor;
            return this;
        }

        public Builder onAfterExecute(PostExecutionHandler handler) {
            this.afterInterceptor = handler;
            return this;
        }

        public ToolPolicy build() {
            return new RuleBasedToolPolicy(
                    Map.copyOf(toolDecisions),
                    requireApprovalForHighRisk,
                    beforeInterceptor,
                    afterInterceptor
            );
        }
    }
}

final class DefaultToolPolicy implements ToolPolicy {
    @Override
    public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
        if (tool != null && tool.isHighRisk()) {
            return AuthDecision.requireApproval("1.0", "OPERATOR", "High-risk action '" + tool.name() + "' requires human approval.");
        }
        return AuthDecision.allow();
    }

    @Override
    public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
        return OutputDecision.allow();
    }
}

final class PermissiveToolPolicy implements ToolPolicy {
    @Override
    public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
        return AuthDecision.allow();
    }

    @Override
    public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
        return OutputDecision.allow();
    }
}

final class CompositeToolPolicy implements ToolPolicy {
    private final List<ToolPolicy> policies;

    CompositeToolPolicy(List<ToolPolicy> policies) {
        this.policies = List.copyOf(Objects.requireNonNull(policies, "policies"));
    }

    @Override
    public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
        AuthDecision pendingApproval = null;
        for (ToolPolicy policy : policies) {
            AuthDecision decision = policy.beforeExecute(tool, argumentsJson, context);
            if (decision instanceof AuthDecision.Deny) {
                return decision;
            }
            if (decision instanceof AuthDecision.RequireApproval && pendingApproval == null) {
                pendingApproval = decision;
            }
        }
        return pendingApproval != null ? pendingApproval : AuthDecision.allow();
    }

    @Override
    public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
        ToolOutcome currentOutcome = outcome;
        boolean redacted = false;
        String redactReason = "";
        for (ToolPolicy policy : policies) {
            OutputDecision decision = policy.afterExecute(tool, currentOutcome, context);
            if (decision instanceof OutputDecision.DenyOutput) {
                return decision;
            }
            if (decision instanceof OutputDecision.Redact redact) {
                currentOutcome = redact.sanitizedOutcome();
                redacted = true;
                redactReason = redact.reason();
            }
        }
        return redacted ? OutputDecision.redact(currentOutcome, redactReason) : OutputDecision.allow();
    }
}

final class RuleBasedToolPolicy implements ToolPolicy {
    private final Map<String, AuthDecision> toolDecisions;
    private final boolean requireApprovalForHighRisk;
    private final ToolPolicy beforeInterceptor;
    private final ToolPolicy.Builder.PostExecutionHandler afterInterceptor;

    RuleBasedToolPolicy(
            Map<String, AuthDecision> toolDecisions,
            boolean requireApprovalForHighRisk,
            ToolPolicy beforeInterceptor,
            ToolPolicy.Builder.PostExecutionHandler afterInterceptor
    ) {
        this.toolDecisions = toolDecisions;
        this.requireApprovalForHighRisk = requireApprovalForHighRisk;
        this.beforeInterceptor = beforeInterceptor;
        this.afterInterceptor = afterInterceptor;
    }

    @Override
    public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
        if (beforeInterceptor != null) {
            AuthDecision custom = beforeInterceptor.beforeExecute(tool, argumentsJson, context);
            if (custom.type() != AuthDecision.DecisionType.ALLOW) {
                return custom;
            }
        }
        if (tool != null && toolDecisions.containsKey(tool.name())) {
            return toolDecisions.get(tool.name());
        }
        if (requireApprovalForHighRisk && tool != null && tool.isHighRisk()) {
            return AuthDecision.requireApproval("1.0", "OPERATOR", "High-risk action '" + tool.name() + "' requires human approval.");
        }
        return AuthDecision.allow();
    }

    @Override
    public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
        if (afterInterceptor != null) {
            return afterInterceptor.afterExecute(tool, outcome, context);
        }
        return OutputDecision.allow();
    }
}
