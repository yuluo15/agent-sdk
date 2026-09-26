package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.agent.RunSnapshot;
import com.agent.agentcorelab.agent.RunStatus;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real sample-tool harness: never returns prefilled tool data. */
public final class SampleRun implements AutoCloseable {
    public final UUID runId = UUID.randomUUID();
    public final Workspace workspace;
    public final WorkspaceSession session;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public final List<Artifact> artifacts = new ArrayList<>();
    public final List<RunSnapshot.Observation> observations = new ArrayList<>();
    public final Map<String, String> files = new HashMap<>();
    public final DataAnalysisScenario scenario = new DataAnalysisScenario();
    public ToolContext context;

    public SampleRun(Path root, boolean fixtures) throws IOException {
        Path input = Files.createDirectories(root.resolve("input"));
        workspace = new Workspace(input, root.resolve("output"), root.resolve("traces"));
        if (fixtures) {
            Path source = Path.of("samples", "analysis", "input").toAbsolutePath();
            for (String name : List.of("sales-baseline.csv", "sales-current.csv", "metric-notes.txt")) {
                Files.copy(source.resolve(name), input.resolve(name));
            }
        }
        session = new WorkspaceSession(workspace, runId);
        context = new ToolContext(runId, workspace, Instant.now().plusSeconds(300), cancelled::get, artifacts::add, session);
    }

    public Tool tool(String name) {
        return scenario.tools().stream().filter(tool -> tool.definition().name().equals(name)).findFirst().orElseThrow();
    }

    public ToolOutcome callRaw(String name, String arguments) {
        ToolCall call = new ToolCall("call-" + (observations.size() + 1), name, arguments);
        ToolOutcome result = tool(name).execute(arguments, context);
        observations.add(new RunSnapshot.Observation(call, result));
        return result;
    }

    public ToolOutcome call(String name, JsonNode arguments) throws IOException {
        return callRaw(name, WorkspaceJson.encode(arguments));
    }

    public JsonNode success(String name, JsonNode arguments) throws IOException {
        ToolOutcome result = call(name, arguments);
        ToolOutcome.Success success = assertInstanceOf(ToolOutcome.Success.class, result, () -> result.toString());
        JsonNode parsed = WorkspaceJson.parse(success.outputJson());
        assertTrue(parsed.path("ok").asBoolean());
        assertTrue(WorkspaceJson.byteLength(success.outputJson()) <= WorkspaceSession.MAX_RESULT_BYTES);
        return parsed;
    }

    public String failure(String name, JsonNode arguments) throws IOException {
        return assertInstanceOf(ToolOutcome.Failure.class, call(name, arguments)).code();
    }

    public JsonNode discover() throws IOException {
        JsonNode listing = success("listWorkspaceFiles", WorkspaceJson.object());
        for (JsonNode file : listing.path("data").path("files")) files.put(file.get("name").asText(), file.get("fileId").asText());
        return listing;
    }

    public ObjectNode file(String name) { return WorkspaceJson.object().put("fileId", files.get(name)); }

    public ObjectNode analysis() {
        ObjectNode arguments = WorkspaceJson.object();
        arguments.set("baseline", side("sales-baseline.csv", "2026-08-01", "2026-08-02"));
        arguments.set("comparison", side("sales-current.csv", "2026-09-01", "2026-09-02"));
        return arguments;
    }

    public ObjectNode side(String file, String start, String end) {
        return WorkspaceJson.object().put("fileId", files.get(file)).put("dateColumn", "date")
                .put("startDate", start).put("endDate", end).put("groupColumn", "channel")
                .put("valueColumn", "gross_sales").put("deductionColumn", "refund_amount").put("countColumn", "order_count");
    }

    public ObjectNode report(String body, String... evidenceIds) {
        ObjectNode arguments = WorkspaceJson.object().put("title", "虚构两日数据比较").put("bodyMarkdown", body);
        for (String id : evidenceIds) arguments.withArray("evidenceIds").add(id);
        return arguments;
    }

    public List<String> prepareReport() throws IOException {
        discover();
        success("inspectDataset", file("sales-baseline.csv"));
        success("inspectDataset", file("sales-current.csv"));
        String reference = success("readReference", file("metric-notes.txt")).get("evidenceId").asText();
        String analysis = success("analyzeDataset", analysis()).get("evidenceId").asText();
        return List.of(analysis, reference);
    }

    public RunSnapshot snapshot() {
        return new RunSnapshot(runId, "data-analysis", "Compare configured fictional windows", RunStatus.RUNNING, observations, artifacts,
                new com.agent.agentcorelab.agent.RunMetrics(0, observations.size(), java.time.Duration.ZERO, java.util.Optional.empty()),
                java.util.Optional.empty());
    }

    @Override public void close() { session.close(); }
}
