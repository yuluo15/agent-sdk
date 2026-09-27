package com.agent.agentcorelab.agent;

import java.util.Optional;
import java.util.UUID;

public interface AgentRunner {

    /**
     * Executes a task using the policy configured on {@link AgentSpec#policy()}.
     */
    RunResult run(AgentSpec agent, RunRequest request);

    /**
     * Executes a task with an explicit {@link ExecutionPolicy} override.
     */
    default RunResult run(AgentSpec agent, RunRequest request, ExecutionPolicy policy) {
        return run(agent, request);
    }

    /**
     * Executes in conversational mode (using {@link ExecutionPolicy#conversational()}).
     * <p>
     * If the runner is currently waiting for input from a previous turn, this request's goal
     * is automatically routed as the answer to continue the pending turn.
     */
    default RunResult chat(AgentSpec agent, RunRequest request) {
        return run(agent, request, ExecutionPolicy.conversational());
    }

    RunResult resume(UUID runId, String answer);

    void cancel(UUID runId);

    RunSnapshot snapshot(UUID runId);

    Optional<RunSnapshot> currentSnapshot();
}
