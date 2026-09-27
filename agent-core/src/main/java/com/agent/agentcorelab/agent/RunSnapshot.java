package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public record RunSnapshot(
        UUID runId,
        String agentId,
        String goal,
        RunStatus status,
        List<Observation> observations,
        List<Artifact> artifacts,
        RunMetrics metrics,
        Optional<PendingQuestion> pendingQuestion,
        Optional<TaskBrief> taskBrief,
        Optional<TaskPlan> taskPlan
) {
    public RunSnapshot(
            UUID runId,
            String agentId,
            String goal,
            RunStatus status,
            List<Observation> observations,
            List<Artifact> artifacts,
            RunMetrics metrics,
            Optional<PendingQuestion> pendingQuestion
    ) {
        this(runId, agentId, goal, status, observations, artifacts, metrics, pendingQuestion,
                Optional.empty(), Optional.empty());
    }

    public RunSnapshot {
        observations = List.copyOf(observations != null ? observations : List.of());
        artifacts = List.copyOf(artifacts != null ? artifacts : List.of());
        taskBrief = taskBrief != null ? taskBrief : Optional.empty();
        taskPlan = taskPlan != null ? taskPlan : Optional.empty();
    }

    public record Observation(ToolCall call, ToolOutcome outcome) {
    }
}
