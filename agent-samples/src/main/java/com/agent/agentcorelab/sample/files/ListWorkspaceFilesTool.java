package com.agent.agentcorelab.sample.files;

import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;

public final class ListWorkspaceFilesTool extends SampleTool {
    /** Stateless singleton; all file/evidence state remains in ToolContext.resources(). */
    public static final ListWorkspaceFilesTool INSTANCE = new ListWorkspaceFilesTool();

    public ListWorkspaceFilesTool() {
        super("listWorkspaceFiles", "Discover CSV/TXT/MD files in the configured input top level. Returns opaque run-local fileIds, not paths.",
                objectSchema(WorkspaceJson.object()));
    }

    @Override protected String executeValidated(JsonNode arguments, ToolContext context) throws IOException {
        List<WorkspaceSession.FileEntry> files = context.resources().listFiles(context::checkActive);
        ObjectNode data = WorkspaceJson.object();
        ArrayNode entries = data.putArray("files");
        files.forEach(file -> entries.add(file.toJson()));
        context.checkActive();
        return context.resources().observe(definition().name(), arguments, data,
                files.stream().map(WorkspaceSession.FileEntry::fileId).toList());
    }
}
