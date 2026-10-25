package com.agent.agentcorelab.checkpoint;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.approval.ApprovalBinding;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.plan.TaskBrief;
import com.agent.agentcorelab.agent.plan.TaskPlan;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.PendingQuestion;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.workspace.Artifact;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable snapshot capturing the static resting state of an agent run at:
 * {@link RunStatus#WAITING_INPUT} or {@link RunStatus#WAITING_APPROVAL}.
 * <p>
 * Boundary Invariant: Streaming inference in flight is strictly excluded.
 * Only complete static resting states are persisted.
 */
public record RunCheckpoint(
        UUID runId,
        long version,
        RunStatus status,
        Instant timestamp,
        String agentId,
        AgentSpec spec,
        String goal,
        RunRequest request,
        TaskBrief taskBrief,
        TaskPlan taskPlan,
        List<RunSnapshot.Observation> observations,
        List<Artifact> artifacts,
        PendingQuestion pendingQuestion,
        ApprovalRequest pendingApproval,
        ApprovalBinding pendingBinding,
        ToolCall pendingHighRiskCall,
        List<ToolCall> pendingHighRiskCalls,
        int pendingHighRiskIndex,
        TrustedContext trustedContext,
        RunMetrics metrics,
        int clarificationSubDialogueTurns,
        int corrections,
        String message,
        Map<String, String> metadata
) {
    public RunCheckpoint {
        Objects.requireNonNull(runId, "runId");
        if (version < 1) {
            throw new IllegalArgumentException("Checkpoint version must be >= 1, got: " + version);
        }
        Objects.requireNonNull(status, "status");
        if (status != RunStatus.WAITING_INPUT && status != RunStatus.WAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "Checkpoints are strictly restricted to WAITING_INPUT or WAITING_APPROVAL, got: " + status);
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(request, "request");
        taskBrief = taskBrief != null ? taskBrief : TaskBrief.of(goal);
        taskPlan = taskPlan != null ? taskPlan : TaskPlan.empty(taskBrief.goalVersion());
        observations = observations != null ? List.copyOf(observations) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
        pendingHighRiskCalls = pendingHighRiskCalls != null ? List.copyOf(pendingHighRiskCalls) : List.of();
        trustedContext = trustedContext != null ? trustedContext : request.resolvedTrustedContext();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public boolean isWaitingApproval() {
        return status == RunStatus.WAITING_APPROVAL;
    }

    public boolean isWaitingInput() {
        return status == RunStatus.WAITING_INPUT;
    }

    /**
     * Converts this checkpoint into a read-only {@link RunSnapshot} for client inspection
     * without activating or locking runner execution.
     */
    public RunSnapshot toSnapshot() {
        return new RunSnapshot(
                runId,
                agentId,
                goal,
                status,
                observations,
                artifacts,
                metrics != null ? metrics : new RunMetrics(0, 0, Duration.ZERO, Optional.empty()),
                Optional.ofNullable(pendingQuestion),
                Optional.ofNullable(taskBrief),
                Optional.ofNullable(taskPlan),
                Optional.ofNullable(pendingApproval),
                Optional.ofNullable(pendingBinding)
        );
    }
}
