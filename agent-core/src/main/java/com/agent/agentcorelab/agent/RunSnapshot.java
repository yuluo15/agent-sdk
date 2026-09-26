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
        Optional<PendingQuestion> pendingQuestion
) {
    public RunSnapshot {
        observations = List.copyOf(observations);
        artifacts = List.copyOf(artifacts);
    }

    public record Observation(ToolCall call, ToolOutcome outcome) {
    }
}
