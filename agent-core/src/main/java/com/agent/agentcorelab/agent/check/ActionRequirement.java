package com.agent.agentcorelab.agent.check;

/**
 * Verification constraints and safety bounds for side-effecting operations (updates, payments, cancellations).
 */
public record ActionRequirement(
        boolean requireSuccessfulExecution,
        boolean requireReceipt,
        boolean disallowDestructiveRepair
) {
    public static ActionRequirement none() {
        return new ActionRequirement(false, false, true);
    }

    public static ActionRequirement strictAction(boolean requireReceipt) {
        return new ActionRequirement(true, requireReceipt, true);
    }
}
