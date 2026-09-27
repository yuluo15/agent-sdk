package com.agent.agentcorelab.agent;

public enum RunStatus {
    RUNNING,
    WAITING_INPUT,
    WAITING_APPROVAL,
    COMPLETED,
    STOPPED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == STOPPED || this == FAILED || this == CANCELLED;
    }

    public boolean isWaiting() {
        return this == WAITING_INPUT || this == WAITING_APPROVAL;
    }
}
