package com.agent.agentcorelab.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single dialogue turn in a multi-turn conversation.
 */
public record ChatMessage(Role role, String content, Instant timestamp) {

    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    public ChatMessage(Role role, String content) {
        this(role, content, Instant.now());
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role cannot be null");
        Objects.requireNonNull(content, "content cannot be null");
        timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }
}
