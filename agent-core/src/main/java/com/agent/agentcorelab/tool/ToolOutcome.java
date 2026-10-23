package com.agent.agentcorelab.tool;

public sealed interface ToolOutcome {

    record Success(String outputJson) implements ToolOutcome {
    }

    record Failure(String code, String message, boolean terminal) implements ToolOutcome {
    }
}
