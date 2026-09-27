package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.workspace.Workspace;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A conversational session wrapper over {@link AgentRunner} that provides a natural,
 * single-entrypoint dialogue experience (e.g. for Web, Chat, or Copilot applications).
 * <p>
 * Callers simply invoke {@link #send(String)} on each user turn without needing to
 * manually detect {@link RunStatus#WAITING_INPUT} or manage the bifurcation between
 * {@link AgentRunner#run(AgentSpec, RunRequest)} and {@link AgentRunner#resume(UUID, String)}.
 */
public final class AgentConversation {

    private final AgentRunner runner;
    private final AgentSpec spec;
    private final Workspace workspace;
    private final ExecutionPolicy policy;

    public AgentConversation(AgentRunner runner, AgentSpec spec, Workspace workspace) {
        this(runner, spec, workspace, ExecutionPolicy.conversational());
    }

    public AgentConversation(AgentRunner runner, AgentSpec spec, Workspace workspace, ExecutionPolicy policy) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Sends a user message to this conversation turn.
     * <p>
     * If the agent is currently waiting for input from a previous turn (e.g., via
     * {@code requestClarification}), this message seamlessly answers that question.
     * Otherwise, it initiates a new goal/task.
     *
     * @param message The user message or answer
     * @return The execution result of this turn
     */
    public RunResult send(String message) {
        return send(message, null);
    }

    /**
     * Sends a user message to this conversation turn with a streaming listener.
     *
     * @param message The user message or answer
     * @param listener Consumer for streaming progress events, tool status, and tokens
     * @return The execution result of this turn
     */
    public RunResult send(String message, Consumer<RunStreamChunk> listener) {
        return runner.run(spec, new RunRequest(message, workspace), policy, listener);
    }

    /**
     * Approves a pending high-risk action in this conversation.
     */
    public RunResult approve(String approvalId) {
        return approve(approvalId, null);
    }

    public RunResult approve(String approvalId, Consumer<RunStreamChunk> listener) {
        RunSnapshot snapshot = currentSnapshot().orElseThrow(() ->
                new IllegalStateException("No active conversation to approve action"));
        return runner.approve(snapshot.runId(), approvalId, listener);
    }

    /**
     * Rejects a pending high-risk action in this conversation with an explanation.
     */
    public RunResult reject(String approvalId, String reason) {
        return reject(approvalId, reason, null);
    }

    public RunResult reject(String approvalId, String reason, Consumer<RunStreamChunk> listener) {
        RunSnapshot snapshot = currentSnapshot().orElseThrow(() ->
                new IllegalStateException("No active conversation to reject action"));
        return runner.reject(snapshot.runId(), approvalId, reason, listener);
    }

    /**
     * Returns the latest snapshot of the conversation if any run has occurred.
     */
    public Optional<RunSnapshot> currentSnapshot() {
        return runner.currentSnapshot();
    }

    /**
     * Cancels the active task if one is currently in progress or waiting.
     */
    public void cancel() {
        runner.currentSnapshot().ifPresent(snapshot -> runner.cancel(snapshot.runId()));
    }

    /**
     * Revises the goal, constraints, or deliverables for the current active/waiting task in this conversation.
     *
     * @param revisedBrief The revised task brief with updated objective, constraints, or deliverables
     * @return The execution result after revising the goal
     */
    public RunResult reviseGoal(TaskBrief revisedBrief) {
        return reviseGoal(revisedBrief, null);
    }

    public RunResult reviseGoal(TaskBrief revisedBrief, Consumer<RunStreamChunk> listener) {
        RunSnapshot snapshot = currentSnapshot().orElseThrow(() ->
                new IllegalStateException("No active conversation to revise goal"));
        return runner.reviseGoal(snapshot.runId(), revisedBrief, listener);
    }

    public AgentRunner runner() {
        return runner;
    }

    public AgentSpec spec() {
        return spec;
    }

    public Workspace workspace() {
        return workspace;
    }

    public ExecutionPolicy policy() {
        return policy;
    }
}
