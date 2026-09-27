package com.agent.agentcorelab.tool;

import java.util.Objects;

public record ToolDefinition(String name, String description, String inputSchema, RiskLevel riskLevel) {

    public ToolDefinition(String name, String description, String inputSchema) {
        this(name, description, inputSchema, RiskLevel.READ_ONLY);
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
    }

    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH_RISK;
    }
}
