package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SaveWorkNoteTool implements Tool {
    public static final String NAME = "saveWorkNote";
    private static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Save an intermediate finding, hypothesis, or note to the run workspace. "
                    + "Notes are tagged MODEL_DERIVED and do not by themselves constitute primary calculation evidence.", """
            {"type":"object","properties":{
              "title":{"type":"string","minLength":1,"maxLength":200},
              "content":{"type":"string","minLength":1,"maxLength":16384},
              "sourceRefs":{"type":"array","items":{"type":"string"}}
            },"required":["title","content"],"additionalProperties":false}
            """);

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        context.checkActive();
        JsonNode args = ToolJson.parse(argumentsJson);
        String title = args.path("title").asText();
        String content = args.path("content").asText();
        List<String> sourceRefs = new ArrayList<>();
        if (args.has("sourceRefs") && args.get("sourceRefs").isArray()) {
            for (JsonNode refNode : args.get("sourceRefs")) {
                sourceRefs.add(refNode.asText());
            }
        }
        try {
            WorkspaceSession.WorkItem item = context.resources().saveWorkNote(title, content, sourceRefs);
            return new ToolOutcome.Success(ToolJson.write(Map.of(
                    "ok", true,
                    "noteId", item.itemId(),
                    "title", title,
                    "bytes", item.bytes(),
                    "type", "MODEL_DERIVED"
            )));
        } catch (WorkspaceException e) {
            return new ToolOutcome.Failure(e.code(), e.getMessage(), e.terminal());
        } catch (IOException e) {
            return new ToolOutcome.Failure("STORAGE_FAILED", "Failed to save work note: " + e.getMessage(), true);
        }
    }
}
