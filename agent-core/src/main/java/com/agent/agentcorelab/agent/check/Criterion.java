package com.agent.agentcorelab.agent.check;

import java.util.Objects;

/**
 * Concrete business acceptance criterion for a task execution.
 */
public record Criterion(
        String id,
        String statement,
        boolean required
) {
    public Criterion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(statement, "statement");
    }

    public Criterion(String id, String statement) {
        this(id, statement, true);
    }

    public static Criterion required(String id, String statement) {
        return new Criterion(id, statement, true);
    }

    public static Criterion optional(String id, String statement) {
        return new Criterion(id, statement, false);
    }
}
