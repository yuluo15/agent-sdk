package com.agent.agentcorelab.sample.files;

import com.agent.agentcorelab.agent.RunSnapshot;
import com.agent.agentcorelab.agent.RunStatus;
import com.agent.agentcorelab.sample.analysis.DataAnalysisScenario;
import com.agent.agentcorelab.sample.analysis.SampleRun;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioContractTests {
    @TempDir Path root;

    @Test void pluginsUseTheSameContractsAndShareTheStatelessListingInstance() {
        ScenarioPlugin analysis = new DataAnalysisScenario();
        ScenarioPlugin files = new FileInspectionScenario();
        assertEquals("data-analysis", analysis.id());
        assertEquals("file-inspection", files.id());
        assertEquals(5, analysis.tools().size());
        assertEquals(1, files.tools().size());
        assertSame(analysis.tools().getFirst(), files.tools().getFirst());
        assertSame(analysis.tools(), analysis.tools());
        List<Tool> shared = Stream.concat(analysis.tools().stream(), files.tools().stream()).distinct().toList();
        assertEquals(5, new ToolCatalog(shared).definitions().size());
        assertThrows(UnsupportedOperationException.class, () -> analysis.tools().add(files.tools().getFirst()));
        assertNotEquals(analysis.completionCheck().getClass(), files.completionCheck().getClass());
        assertFalse(analysis.description().isBlank());
        assertFalse(files.instructions().isBlank());
    }

    @Test void fileInspectionNeedsARealSuccessfulListingButNoReport() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            FileInspectionScenario scenario = new FileInspectionScenario();
            assertFalse(scenario.completionCheck().check(run.snapshot(), "声称列举过").complete());
            Files.writeString(run.workspace.inputRoot().resolve("actual.txt"), "actual");
            run.discover();
            assertTrue(scenario.completionCheck().check(run.snapshot(), "发现 actual.txt").complete());
            assertTrue(run.artifacts.isEmpty());
            assertFalse(new DataAnalysisScenario().completionCheck().check(run.snapshot(), "发现 actual.txt").complete());
            assertFalse(scenario.completionCheck().check(run.snapshot(), "").complete());
            RunSnapshot wrongRun = new RunSnapshot(UUID.randomUUID(), scenario.id(), "list", RunStatus.RUNNING, run.observations, List.of(),
                    run.snapshot().metrics(), java.util.Optional.empty());
            assertFalse(scenario.completionCheck().check(wrongRun, "发现 actual.txt").complete());
        }
    }

    @Test void genuineEmptyListingIsDifferentFromFakeSuccessOrFailure() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            FileInspectionCompletionCheck check = new FileInspectionCompletionCheck();
            ToolCall call = new ToolCall("fake", "listWorkspaceFiles", "{}");
            for (ToolOutcome result : List.of(new ToolOutcome.Success("{\"ok\":true}"),
                    new ToolOutcome.Failure("WORKSPACE_IO_ERROR", "Cannot list", true),
                    new ToolOutcome.Success("not JSON"))) {
                RunSnapshot fake = new RunSnapshot(run.runId, "file-inspection", "list", RunStatus.RUNNING,
                        List.of(new RunSnapshot.Observation(call, result)), List.of(), run.snapshot().metrics(), java.util.Optional.empty());
                assertFalse(check.check(fake, "已经列举").complete());
            }
            assertTrue(run.discover().path("data").path("files").isEmpty());
            assertTrue(check.check(run.snapshot(), "目录没有受支持文件").complete());
        }
    }

    @Test void sharedToolsDoNotLeakFileIdsBetweenRuns() throws Exception {
        try (SampleRun first = new SampleRun(root.resolve("first"), true); SampleRun second = new SampleRun(root.resolve("second"), true)) {
            assertSame(first.tool("listWorkspaceFiles"), second.tool("listWorkspaceFiles"));
            first.discover();
            second.discover();
            assertNotEquals(first.files.get("sales-baseline.csv"), second.files.get("sales-baseline.csv"));
            assertEquals("UNKNOWN_FILE_ID", second.failure("inspectDataset", first.file("sales-baseline.csv")));
        }
    }

    @Test void publishedSchemasAreStrictAndValidateWithNetworknt() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (Tool tool : new DataAnalysisScenario().tools()) {
            JsonNode node = WorkspaceJson.parse(tool.definition().inputSchema());
            assertStrictSchema(node);
            var schema = factory.getSchema(node);
            assertFalse(schema.validate(WorkspaceJson.object().put("unexpected", true)).isEmpty());
            if (tool.definition().name().equals("listWorkspaceFiles")) {
                assertTrue(schema.validate(WorkspaceJson.object()).isEmpty());
            } else {
                assertFalse(schema.validate(WorkspaceJson.object()).isEmpty());
            }
        }
        Tool analyze = new DataAnalysisScenario().tools().stream().filter(tool -> tool.definition().name().equals("analyzeDataset")).findFirst().orElseThrow();
        var schema = factory.getSchema(WorkspaceJson.parse(analyze.definition().inputSchema()));
        try (SampleRun run = new SampleRun(root, true)) {
            run.discover();
            assertTrue(schema.validate(run.analysis()).isEmpty());
            var unknown = run.analysis();
            ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.get("baseline")).put("expression", "arbitrary()");
            assertFalse(schema.validate(unknown).isEmpty());
        }
    }

    private static void assertStrictSchema(JsonNode schema) {
        String type = schema.path("type").asText();
        assertFalse(type.isBlank());
        switch (type) {
            case "object" -> {
                assertTrue(schema.has("additionalProperties"));
                assertFalse(schema.get("additionalProperties").asBoolean());
                schema.get("properties").forEach(ScenarioContractTests::assertStrictSchema);
            }
            case "array" -> {
                assertTrue(schema.has("minItems"));
                assertTrue(schema.has("maxItems"));
                assertStrictSchema(schema.get("items"));
            }
            case "string" -> {
                assertTrue(schema.has("minLength"));
                assertTrue(schema.has("maxLength"));
            }
            case "integer" -> {
                assertTrue(schema.has("minimum"));
                assertTrue(schema.has("maximum"));
            }
            default -> fail("Unexpected sample schema type " + type);
        }
    }
}
