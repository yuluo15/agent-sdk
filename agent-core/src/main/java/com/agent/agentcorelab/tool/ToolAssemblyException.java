package com.agent.agentcorelab.tool;

/**
 * Thrown when tool assembly fails due to minimum-privilege boundary violations,
 * missing required tools in the candidate pool, or tenant/role isolation mismatches.
 */
public class ToolAssemblyException extends RuntimeException {

    private final String agentId;
    private final String toolName;
    private final String violationCode;

    public ToolAssemblyException(String agentId, String toolName, String violationCode, String message) {
        super(message);
        this.agentId = agentId;
        this.toolName = toolName;
        this.violationCode = violationCode;
    }

    public ToolAssemblyException(String toolName, String violationCode, String message) {
        this(null, toolName, violationCode, message);
    }

    public ToolAssemblyException(String message) {
        this(null, null, "ASSEMBLY_FAILED", message);
    }

    public String agentId() {
        return agentId;
    }

    public String toolName() {
        return toolName;
    }

    public String violationCode() {
        return violationCode;
    }
}
