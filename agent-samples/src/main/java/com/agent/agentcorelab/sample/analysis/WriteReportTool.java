package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.sample.files.SampleTool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WriteReportTool extends SampleTool {
    public WriteReportTool() {
        super("writeReport", "Create a new Markdown report with a program-generated evidence appendix. Cite this run's successful analysis and references. No filename/path parameter; identical content reuses its artifact.", schema());
    }

    private static ObjectNode schema() {
        ObjectNode fields = WorkspaceJson.object();
        fields.set("title", textSchema(1, 200).put("pattern", "^[^\\r\\n]+$"));
        fields.set("bodyMarkdown", textSchema(1, WorkspaceSession.MAX_BODY_BYTES));
        fields.set("evidenceIds", arraySchema(textSchema(1, 64), 1, 30, false));
        return objectSchema(fields, "title", "bodyMarkdown", "evidenceIds");
    }

    @Override protected String executeValidated(JsonNode arguments, ToolContext context) throws IOException {
        List<String> ids = new ArrayList<>();
        arguments.get("evidenceIds").forEach(id -> ids.add(id.asText()));
        String title = arguments.get("title").asText();
        String body = arguments.get("bodyMarkdown").asText();
        if (context.resources().evidence(ids).stream().noneMatch(item -> item.tool().equals("analyzeDataset"))) {
            throw CsvDataset.error("ANALYSIS_REQUIRED", "Report must cite a successful analysis");
        }
        Artifact artifact = context.resources().writeReport(title, body, ids, context::checkActive);
        // Do not check cancellation here: the complete artifact really exists and must be recorded.
        context.artifactSink().accept(artifact);
        ObjectNode data = WorkspaceJson.object().put("artifactId", artifact.id())
                .put("relativePath", context.runId() + "/" + artifact.path().getFileName())
                .put("sha256", artifact.contentDigest());
        ArrayNode citations = data.putArray("evidenceIds");
        artifact.evidenceIds().forEach(citations::add);
        ObjectNode sources = WorkspaceJson.object().put("title", title)
                .put("bodySha256", WorkspaceSession.digest(body.getBytes(StandardCharsets.UTF_8)));
        sources.set("evidenceIds", citations.deepCopy());
        return context.resources().observe(definition().name(), sources, data, List.of());
    }
}
