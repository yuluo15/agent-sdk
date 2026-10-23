package com.agent.agentcorelab.tool;

/**
 * Risk classification for tools to support Human-in-the-Loop approval workflows.
 */
public enum RiskLevel {
    /**
     * Read-only or safe tool. Executes automatically without human approval.
     */
    READ_ONLY,

    /**
     * Mutating, critical, or high-risk tool (e.g. delete, restart, purchase, grant permissions).
     * The engine will intercept invocation, set status to WAITING_APPROVAL, and require explicit confirmation.
     */
    HIGH_RISK
}
