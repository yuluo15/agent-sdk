package com.agent.agentcorelab.agent;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RunStreamChunk(
        Type type,
        String content,
        String toolName,
        String callId,
        RunStatus status,
        Map<String, Object> metadata
) {
    public enum Type {
        TEXT_DELTA,
        THINKING_DELTA,
        TOOL_STARTED,
        TOOL_FINISHED,
        WAITING_INPUT,
        STATUS_CHANGED,
        PLAN_UPDATED,
        BRIEF_UPDATED
    }

    public RunStreamChunk {
        Objects.requireNonNull(type, "type");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RunStreamChunk text(String delta) {
        return new RunStreamChunk(Type.TEXT_DELTA, delta, null, null, null, Map.of());
    }

    public static RunStreamChunk thinking(String delta) {
        return new RunStreamChunk(Type.THINKING_DELTA, delta, null, null, null, Map.of());
    }

    public static RunStreamChunk toolStarted(String callId, String toolName, Map<String, Object> metadata) {
        return new RunStreamChunk(Type.TOOL_STARTED, null, toolName, callId, null, metadata);
    }

    public static RunStreamChunk toolFinished(String callId, String toolName, String outcome) {
        return new RunStreamChunk(Type.TOOL_FINISHED, outcome, toolName, callId, null, Map.of());
    }

    public static RunStreamChunk waitingInput(String question, List<String> options) {
        return new RunStreamChunk(Type.WAITING_INPUT, question, null, null, RunStatus.WAITING_INPUT,
                Map.of("options", options != null ? options : List.of()));
    }

    public static RunStreamChunk statusChanged(RunStatus status) {
        return new RunStreamChunk(Type.STATUS_CHANGED, null, null, null, status, Map.of());
    }

    public static RunStreamChunk planUpdated(int planRevision, int goalVersion, String reason, List<Map<String, Object>> steps) {
        return new RunStreamChunk(Type.PLAN_UPDATED, reason, null, null, null, Map.of(
                "planRevision", planRevision,
                "goalVersion", goalVersion,
                "steps", steps != null ? steps : List.of()
        ));
    }

    public static RunStreamChunk briefUpdated(int goalVersion, String objective, List<String> deliverables) {
        return new RunStreamChunk(Type.BRIEF_UPDATED, objective, null, null, null, Map.of(
                "goalVersion", goalVersion,
                "deliverables", deliverables != null ? deliverables : List.of()
        ));
    }
}
