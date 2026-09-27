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
        return run(agent, request, policy, null);
    }

    /**
     * Executes a task with an explicit {@link ExecutionPolicy} override and a streaming listener for
     * real-time progress events, tool lifecycle, and model token chunks.
     */
    default RunResult run(AgentSpec agent, RunRequest request, ExecutionPolicy policy, java.util.function.Consumer<RunStreamChunk> listener) {
        return run(agent, request);
    }

    /**
     * Executes a task using {@link AgentSpec#policy()} and a streaming listener.
     */
    default RunResult run(AgentSpec agent, RunRequest request, java.util.function.Consumer<RunStreamChunk> listener) {
        return run(agent, request, agent.policy(), listener);
    }

    /**
     * Executes in conversational mode (using {@link ExecutionPolicy#conversational()}).
     * <p>
     * If the runner is currently waiting for input from a previous turn, this request's goal
     * is automatically routed as the answer to continue the pending turn.
     */
    default RunResult chat(AgentSpec agent, RunRequest request) {
        return run(agent, request, ExecutionPolicy.conversational(), null);
    }

    /**
     * Executes in conversational mode with a streaming listener.
     */
    default RunResult chat(AgentSpec agent, RunRequest request, java.util.function.Consumer<RunStreamChunk> listener) {
        return run(agent, request, ExecutionPolicy.conversational(), listener);
    }

    RunResult resume(UUID runId, String answer);

    default RunResult resume(UUID runId, String answer, java.util.function.Consumer<RunStreamChunk> listener) {
        return resume(runId, answer);
    }

    void cancel(UUID runId);

    RunSnapshot snapshot(UUID runId);

    Optional<RunSnapshot> currentSnapshot();

    /**
     * Revises the active task's goal, constraints, or deliverables in place without restarting the run.
     * Increments the goalVersion, supersedes in-flight questions, and instructs
     * the model to adapt its plan and actions to the revised goal.
     */
    default RunResult reviseGoal(UUID runId, TaskBrief revisedBrief) {
        return reviseGoal(runId, revisedBrief, null);
    }

    default RunResult reviseGoal(UUID runId, TaskBrief revisedBrief, java.util.function.Consumer<RunStreamChunk> listener) {
        throw new UnsupportedOperationException("Goal revision is not supported by this runner");
    }
}
