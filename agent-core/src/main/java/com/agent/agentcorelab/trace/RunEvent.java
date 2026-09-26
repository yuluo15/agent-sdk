package com.agent.agentcorelab.trace;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(long sequence, UUID runId, Instant occurredAt, Type type, String summary) {

    public enum Type {
        RUN_STARTED,
        MODEL_STARTED,
        MODEL_FINISHED,
        TOOL_STARTED,
        TOOL_FINISHED,
        WAITING_INPUT,
        INPUT_RECEIVED,
        RUN_FINISHED
    }
}
