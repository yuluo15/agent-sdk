package com.agent.agentcorelab.tool;

import java.util.Objects;

/**
 * Metadata definition and governance attributes of an Agent tool.
 *
 * @param name        Unique tool name, matching {@code [a-zA-Z0-9_-]{1,64}}.
 * @param description Detailed description for LLM reasoning and selection.
 * @param inputSchema JSON schema string defining input parameters.
 * @param riskLevel   HITL safety risk level ({@link RiskLevel#READ_ONLY} or {@link RiskLevel#HIGH_RISK}).
 * @param semantics   Standard governance semantics ({@link ToolSemantics}).
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchema,
        RiskLevel riskLevel,
        ToolSemantics semantics
) {

    public ToolDefinition(String name, String description, String inputSchema) {
        this(name, description, inputSchema, RiskLevel.READ_ONLY, ToolSemantics.readOnlySafe());
    }

    public ToolDefinition(String name, String description, String inputSchema, RiskLevel riskLevel) {
        this(name, description, inputSchema, riskLevel, ToolSemantics.defaultFor(riskLevel));
    }

    public static ToolDefinition of(String name, String description) {
        String desc = description != null && !description.isBlank() ? description : "Tool " + name;
        return new ToolDefinition(name, desc, "{\"type\":\"object\"}");
    }

    public ToolDefinition {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Tool name must contain 1 to 64 letters, digits, underscores or hyphens");
        }
        if (description == null || description.isBlank() || inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("Tool description and input schema are required");
        }
        if (riskLevel == null) {
            riskLevel = RiskLevel.READ_ONLY;
        }
        if (semantics == null) {
            semantics = ToolSemantics.defaultFor(riskLevel);
        }
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH_RISK;
    }

    public boolean isReadOnly() {
        return semantics.isGuaranteedReadOnly();
    }

    public boolean isDestructive() {
        return semantics.destructive() == SemanticHint.YES;
    }

    /**
     * Returns a copy with overridden semantics merged with host rules.
     */
    public ToolDefinition withOverriddenSemantics(ToolSemantics hostOverride) {
        if (hostOverride == null) {
            return this;
        }
        return new ToolDefinition(name, description, inputSchema, riskLevel, semantics.overrideWith(hostOverride));
    }

    /**
     * Returns a copy with explicitly replaced semantics.
     */
    public ToolDefinition withSemantics(ToolSemantics newSemantics) {
        return new ToolDefinition(name, description, inputSchema, riskLevel, newSemantics != null ? newSemantics : ToolSemantics.defaultFor(riskLevel));
    }
}
