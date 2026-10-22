package com.agent.agentcorelab.trace;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(long sequence, UUID runId, Instant occurredAt, Type type, String summary) {

    public enum Type {
        RUN_STARTED,
        MODEL_STARTED,
        MODEL_FINISHED,
        MODEL_RETRY,
        COMPACTION_TRIGGERED,
        DELIVERABLE_RETRY,
        TOOL_STARTED,
        TOOL_FINISHED,
        WAITING_INPUT,
        INPUT_RECEIVED,
        APPROVAL_REQUESTED,
        APPROVAL_RESOLVED,
        PLAN_UPDATED,
        BRIEF_UPDATED,
        DELIVERABLE_VERIFIED,
        RUN_TIMEOUT,
        RUN_EVICTED,
        RUN_FINISHED
    }
}
