package com.agent.agentcorelab.model;

import java.util.Objects;

/**
 * A streamed chunk from the model provider.
 */
public record ModelStreamChunk(Type type, String text) {
    public enum Type {
        TEXT,
        THINKING
    }

    public ModelStreamChunk {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
    }

    public static ModelStreamChunk text(String text) {
        return new ModelStreamChunk(Type.TEXT, text);
    }

    public static ModelStreamChunk thinking(String thinking) {
        return new ModelStreamChunk(Type.THINKING, thinking);
    }
}
