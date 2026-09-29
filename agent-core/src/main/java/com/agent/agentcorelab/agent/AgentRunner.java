package com.agent.agentcorelab.agent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

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
    default RunResult run(AgentSpec agent, RunRequest request, ExecutionPolicy policy, Consumer<RunStreamChunk> listener) {
        return run(agent, request);
    }

    /**
     * Executes a task using {@link AgentSpec#policy()} and a streaming listener.
     */
    default RunResult run(AgentSpec agent, RunRequest request, Consumer<RunStreamChunk> listener) {
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
    default RunResult chat(AgentSpec agent, RunRequest request, Consumer<RunStreamChunk> listener) {
        return run(agent, request, ExecutionPolicy.conversational(), listener);
    }

    RunResult resume(UUID runId, String answer);

    default RunResult resume(UUID runId, String answer, Consumer<RunStreamChunk> listener) {
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

    default RunResult reviseGoal(UUID runId, TaskBrief revisedBrief, Consumer<RunStreamChunk> listener) {
        throw new UnsupportedOperationException("Goal revision is not supported by this runner");
    }

    /**
     * Approves a pending high-risk tool call, resuming execution to execute the tool and continue the loop.
     */
    default RunResult approve(UUID runId, String approvalId) {
        return approve(runId, approvalId, null);
    }

    default RunResult approve(UUID runId, String approvalId, Consumer<RunStreamChunk> listener) {
        throw new UnsupportedOperationException("Human-in-the-loop approval is not supported by this runner");
    }

    /**
     * Rejects a pending high-risk tool call, feeding the user rejection reason back to the model as an error.
     */
    default RunResult reject(UUID runId, String approvalId, String reason) {
        return reject(runId, approvalId, reason, null);
    }

    default RunResult reject(UUID runId, String approvalId, String reason, Consumer<RunStreamChunk> listener) {
        throw new UnsupportedOperationException("Human-in-the-loop rejection is not supported by this runner");
    }

    /**
     * Prunes runs that have exceeded their waiting TTL while waiting for input or approval.
     *
     * @return count of runs pruned
     */
    default int pruneExpiredRuns() {
        return 0;
    }

    /**
     * Cleans up terminal runs (COMPLETED, FAILED, CANCELLED, STOPPED) from memory.
     *
     * @return count of runs removed
     */
    default int cleanupTerminatedRuns() {
        return 0;
    }

    /**
     * Returns total count of active runs currently retained in the runner.
     */
    default int activeRunCount() {
        return 0;
    }

    /**
     * Explicitly closes and evicts a run and its resources from the runner.
     */
    default void closeRun(UUID runId) {
    }

    /**
     * Returns the conversation memory manager if supported by this runner.
     */
    default com.agent.agentcorelab.memory.ConversationMemory memory() {
        return null;
    }

    /**
     * Returns the user profile memory manager if supported by this runner.
     */
    default com.agent.agentcorelab.memory.UserProfileMemory userProfiles() {
        return null;
    }
}
