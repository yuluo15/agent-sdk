package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioExecutionTests {
    @Autowired
    private ScenarioRegistry scenarios;

    @TempDir
    Path root;

    @Test
    void realFileScenarioRunsThroughTheSameEngineWithoutRequiringASalesReport() throws Exception {
        Path input = Files.createDirectory(root.resolve("input"));
        Files.writeString(input.resolve("manual.txt"), "Approved fictional sample material");
        Workspace workspace = new Workspace(input, root.resolve("output"), root.resolve("traces"));
        ModelGateway model = (instructions, goal, definitions) -> new ModelSession() {
            private boolean requested;
            private boolean observed;

            @Override
            public ModelResponse nextResponse(Duration timeout) {
                if (!requested) {
                    requested = true;
                    return new ModelResponse("", List.of(new ToolCall("files", "listWorkspaceFiles", "{}")),
                            ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
                }
                assertTrue(observed);
                return new ModelResponse("The controlled workspace contains manual.txt.", List.of(),
                        ModelResponse.FinishReason.STOP, Optional.empty());
            }

            @Override
            public long estimatedInputTokens() {
                return 128;
            }

            @Override
            public void appendToolResult(String callId, ToolOutcome outcome) {
                assertEquals("files", callId);
                assertInstanceOf(ToolOutcome.Success.class, outcome);
                assertTrue(((ToolOutcome.Success) outcome).outputJson().contains("manual.txt"));
                observed = true;
            }

            @Override
            public void appendUserMessage(String message) {
                fail("No extra user message expected");
            }

            @Override
            public void appendRuntimeFeedback(String feedback) {
                fail("The file scenario must not impose a report requirement: " + feedback);
            }

            @Override
            public void close() {
            }
        };
        try (var runner = new DefaultAgentRunner(model, scenarios.tools(), new JsonlRunEventSink(workspace.traceRoot()))) {
            var result = runner.run(scenarios.require("file-inspection").spec(), new RunRequest("List approved files", workspace));
            assertEquals(RunStatus.COMPLETED, result.snapshot().status());
            assertTrue(result.snapshot().artifacts().isEmpty());
            assertEquals(1, result.snapshot().observations().size());
            assertTrue(Files.exists(workspace.traceRoot().resolve(result.snapshot().runId() + ".jsonl")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void analysisUsesRealEvidenceThroughRunnerAndCanRepairPrematureCompletion(boolean prematureAnswer) throws Exception {
        Path input = Files.createDirectory(root.resolve("input"));
        for (String name : List.of("sales-baseline.csv", "sales-current.csv", "metric-notes.txt")) {
            Files.copy(Path.of("samples", "analysis", "input", name), input.resolve(name));
        }
        Workspace workspace = new Workspace(input, root.resolve("output"), root.resolve("traces"));
        AtomicInteger repairs = new AtomicInteger();
        ModelGateway model = (instructions, goal, definitions) -> new ModelSession() {
            private int step = prematureAnswer ? -1 : 0;
            private final Map<String, JsonNode> results = new HashMap<>();
            private final Map<String, String> files = new HashMap<>();

            @Override public ModelResponse nextResponse(Duration timeout) {
                return switch (step++) {
                    case -1 -> answer("尚未调用工具就声称完成");
                    case 0 -> calls(new ToolCall("files", "listWorkspaceFiles", "{}"));
                    case 1 -> {
                        results.get("files").path("data").path("files")
                                .forEach(file -> files.put(file.path("name").asText(), file.path("fileId").asText()));
                        yield calls(new ToolCall("baseline", "inspectDataset", ToolJson.write(Map.of("fileId", files.get("sales-baseline.csv")))),
                                new ToolCall("comparison", "inspectDataset", ToolJson.write(Map.of("fileId", files.get("sales-current.csv")))));
                    }
                    case 2 -> calls(new ToolCall("reference", "readReference", ToolJson.write(Map.of("fileId", files.get("metric-notes.txt")))));
                    case 3 -> {
                        var arguments = WorkspaceJson.object();
                        for (String side : List.of("baseline", "comparison")) {
                            String start = side.equals("baseline") ? "2026-08-01" : "2026-09-01";
                            String end = side.equals("baseline") ? "2026-08-02" : "2026-09-02";
                            arguments.set(side, WorkspaceJson.object()
                                    .put("fileId", files.get(side.equals("baseline") ? "sales-baseline.csv" : "sales-current.csv"))
                                    .put("dateColumn", "date").put("startDate", start).put("endDate", end)
                                    .put("groupColumn", "channel").put("valueColumn", "gross_sales")
                                    .put("deductionColumn", "refund_amount").put("countColumn", "order_count"));
                        }
                        yield calls(new ToolCall("analysis", "analyzeDataset", arguments.toString()));
                    }
                    case 4 -> calls(new ToolCall("report", "writeReport", ToolJson.write(Map.of(
                            "title", "虚构两日窗口比较", "bodyMarkdown", "比较两个指定的两日窗口，不推断外部因果。",
                            "evidenceIds", List.of(results.get("analysis").path("evidenceId").asText(),
                                    results.get("reference").path("evidenceId").asText())))));
                    case 5 -> answer("报告已生成，包含指定窗口的分析证据和指标说明。");
                    default -> throw new AssertionError("Unexpected model request");
                };
            }

            private ModelResponse calls(ToolCall... calls) {
                return new ModelResponse("", List.of(calls), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
            }

            private ModelResponse answer(String text) {
                return new ModelResponse(text, List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }

            @Override public void appendToolResult(String callId, ToolOutcome outcome) {
                var success = assertInstanceOf(ToolOutcome.Success.class, outcome, () -> outcome.toString());
                results.put(callId, ToolJson.parse(success.outputJson()));
            }

            @Override public void appendRuntimeFeedback(String feedback) {
                assertTrue(prematureAnswer);
                assertTrue(results.isEmpty());
                assertTrue(feedback.contains("INCOMPLETE_DELIVERABLE"));
                repairs.incrementAndGet();
            }

            @Override public long estimatedInputTokens() { return 1024; }
            @Override public void appendUserMessage(String message) { fail("No clarification expected"); }
            @Override public void close() { }
        };
        try (var runner = new DefaultAgentRunner(model, scenarios.tools(), new JsonlRunEventSink(workspace.traceRoot()))) {
            var result = runner.run(scenarios.require("data-analysis").spec(), new RunRequest("比较指定的虚构两日窗口", workspace));
            assertEquals(RunStatus.COMPLETED, result.snapshot().status(), result::message);
            assertEquals(prematureAnswer ? 1 : 0, repairs.get());
            assertEquals(prematureAnswer ? 7 : 6, result.snapshot().metrics().modelRequests());
            assertEquals(6, result.snapshot().metrics().toolCalls());
            assertEquals(6, result.snapshot().observations().size());
            assertEquals(1, result.snapshot().artifacts().size());
            var artifact = result.snapshot().artifacts().getFirst();
            WorkspaceSession.verifyArtifact(artifact);
            assertTrue(Files.readString(artifact.path()).contains("-400.00"));
            assertTrue(scenarios.require("data-analysis").spec().completionCheck().check(result.snapshot(), result.message()).complete());
        }
    }
}
