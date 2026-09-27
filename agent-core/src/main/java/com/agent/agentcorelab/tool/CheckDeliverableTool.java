package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public final class CheckDeliverableTool implements Tool {
    public static final String NAME = "checkDeliverable";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Check whether a generated deliverable artifact meets required constraints, "
                    + "current goalVersion, and evidence integrity.", """
            {"type":"object","properties":{
              "artifactId":{"type":"string","minLength":1,"maxLength":100}
            },"required":["artifactId"],"additionalProperties":false}
            """);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        context.checkActive();
        JsonNode args = ToolJson.parse(argumentsJson);
        String artifactId = args.path("artifactId").asText();
        if (artifactId.isBlank()) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "artifactId must not be blank", false);
        }
        WorkspaceSession.DeliverableCheckResult result = context.resources().checkDeliverable(artifactId);
        return new ToolOutcome.Success(ToolJson.write(Map.of(
                "passed", result.passed(),
                "artifactId", result.artifactId(),
                "goalVersion", result.goalVersion(),
                "issues", result.issues()
        )));
    }
}
