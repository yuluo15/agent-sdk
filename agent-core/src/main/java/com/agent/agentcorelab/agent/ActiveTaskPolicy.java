package com.agent.agentcorelab.agent;

/**
 * Defines the strategy for handling new task submissions when an {@link AgentRunner}
 * currently has an active (non-terminal) task.
 */
public enum ActiveTaskPolicy {

    /**
     * Strict / deterministic mode (default):
     * Rejects any new task when there is an active (RUNNING or WAITING_INPUT) task
     * by throwing {@link ActiveTaskConflictException}.
     */
    REJECT,

    /**
     * Conversational mode:
     * If the current task is suspended in {@link RunStatus#WAITING_INPUT},
     * the new request's goal is automatically routed as the answer to the pending question,
     * seamlessly continuing the conversation. If no task is active, starts a new task normally.
     */
    RESUME_IF_WAITING,

    /**
     * Preemptive mode:
     * If the current task is suspended in {@link RunStatus#WAITING_INPUT},
     * it is immediately cancelled (with reason "PREEMPTED") and the new task is started.
     * If the current task is RUNNING, rejects with {@link ActiveTaskConflictException}.
     */
    PREEMPT_IF_WAITING,

    /**
     * Aggressive preemption mode:
     * Preempts waiting tasks immediately; for running tasks, requests cooperative cancellation
     * before throwing {@link ActiveTaskConflictException}.
     */
    PREEMPT_AND_ABORT
}
