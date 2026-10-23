package com.agent.agentcorelab.tool;

import java.util.Objects;

/**
 * A decorating {@link Tool} that overrides the original {@link ToolDefinition}
 * while delegating execution to the underlying tool instance.
 * <p>
 * Commonly used when the enterprise host application or policy engine overrides
 * untrusted tool claims (e.g. MCP tools claiming to be read-only).
 */
public record OverriddenTool(Tool delegate, ToolDefinition definition) implements Tool {

    public OverriddenTool {
        Objects.requireNonNull(delegate, "delegate cannot be null");
        Objects.requireNonNull(definition, "definition cannot be null");
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        return delegate.execute(argumentsJson, context);
    }
}
