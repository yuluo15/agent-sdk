package com.agent.agentcorelab.tool.annotation;

import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.SemanticHint;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a Java method as an executable Agent Tool that can be called by LLMs in a ReAct loop.
 * <p>
 * Methods annotated with {@code @AgentTool} can take typed business parameters (annotated with
 * {@link ToolParam}) as well as framework parameters like {@link com.agent.agentcorelab.tool.ToolContext}.
 * The SDK automatically infers JSON Schema, converts arguments, handles errors, and enforces risk policies.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /**
     * The unique name of the tool, matching {@code [a-zA-Z0-9_-]{1,64}}.
     * If left empty, the method name is used by default.
     */
    String name() default "";

    /**
     * Clear, comprehensive description explaining what this tool does and when the Agent should call it.
     */
    String description();

    /**
     * Risk level for human-in-the-loop (HITL) safety gating.
     * Defaults to {@link RiskLevel#READ_ONLY}.
     * When set to {@link RiskLevel#HIGH_RISK}, executions will automatically pause
     * and request human approval.
     */
    RiskLevel riskLevel() default RiskLevel.READ_ONLY;

    /**
     * Governance semantic hint: whether this tool is read-only.
     * Defaults to {@link SemanticHint#UNKNOWN} (inferred from {@link #riskLevel()} if unspecified).
     */
    SemanticHint readOnly() default SemanticHint.UNKNOWN;

    /**
     * Governance semantic hint: whether this tool performs destructive mutations.
     * Defaults to {@link SemanticHint#UNKNOWN} (inferred from {@link #riskLevel()} if unspecified).
     */
    SemanticHint destructive() default SemanticHint.UNKNOWN;

    /**
     * Governance semantic hint: whether this tool execution is idempotent.
     * Defaults to {@link SemanticHint#UNKNOWN}.
     */
    SemanticHint idempotent() default SemanticHint.UNKNOWN;

    /**
     * Governance semantic hint: whether this tool interacts with an open-world external system.
     * Defaults to {@link SemanticHint#UNKNOWN}.
     */
    SemanticHint openWorld() default SemanticHint.UNKNOWN;
}
