package com.agent.agentcorelab.tool;

public interface Tool {

    ToolDefinition definition();

    ToolOutcome execute(String argumentsJson, ToolContext context);
}
