package com.agent.agentcorelab.tool.builtin;

import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/**
 * Controlled tool to read paginated slices from offloaded workspace items,
 * preventing context window exhaustion on large tool outputs.
 */
public final class ReadWorkItemTool implements Tool {
    public static final String NAME = "readWorkItem";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Read paginated lines from a previously offloaded workspace item. "
                    + "Use this to inspect large tool outputs without loading everything into context.",
            """
            {"type":"object","properties":{
              "itemId":{"type":"string","minLength":1,"maxLength":128},
              "startLine":{"type":"integer","minimum":1},
              "maxLines":{"type":"integer","minimum":1,"maximum":100}
            },"required":["itemId"],"additionalProperties":false}
            """);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        if (context == null || context.resources() == null) {
            return new ToolOutcome.Failure("WORKSPACE_UNAVAILABLE", "Workspace session is required to read work items", true);
        }
        try {
            JsonNode args = ToolJson.parse(argumentsJson);
            String itemId = args.path("itemId").asText();
            if (itemId == null || itemId.isBlank()) {
                return new ToolOutcome.Failure("INVALID_ARGUMENT", "itemId is required", false);
            }
            int startLine = args.has("startLine") ? args.path("startLine").asInt(1) : 1;
            int maxLines = args.has("maxLines") ? Math.min(100, Math.max(1, args.path("maxLines").asInt(30))) : 30;

            WorkspaceSession.WorkSlice slice = context.resources().readWorkItemSlice(itemId, startLine, maxLines);
            return new ToolOutcome.Success(ToolJson.write(Map.of(
                    "ok", true,
                    "itemId", slice.itemId(),
                    "startLine", slice.startLine(),
                    "lineCount", slice.lineCount(),
                    "totalLines", slice.totalLines(),
                    "hasMore", slice.hasMore(),
                    "nextLine", slice.nextLine(),
                    "content", slice.content()
            )));
        } catch (WorkspaceException ex) {
            return new ToolOutcome.Failure(ex.code(), ex.getMessage(), false);
        } catch (IOException ex) {
            return new ToolOutcome.Failure("STORAGE_FAILED", "Failed to read work item", true);
        } catch (RuntimeException ex) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Invalid arguments for readWorkItem", false);
        }
    }
}
