package com.agent.agentcorelab.tool;

public final class ClarificationTool implements Tool {
    public static final String NAME = "requestClarification";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Ask the current user for missing information. Must be the only tool call in this response. "
                    + "An answer supplies information, never additional permissions.", """
            {"type":"object","properties":{
              "question":{"type":"string","minLength":1,"maxLength":2000},
              "options":{"type":"array","maxItems":4,"items":{"type":"string","minLength":1,"maxLength":200}}
            },"required":["question"],"additionalProperties":false}
            """);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        throw new IllegalStateException("Clarification is controlled by the Runner");
    }
}
