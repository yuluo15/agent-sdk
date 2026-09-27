package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.CheckDeliverableTool;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.SaveWorkNoteTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.UpdatePlanTool;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitPlanningAndTaskBriefTests {

    @TempDir
    Path tempDir;

    private Path inputDir;
    private Path outputDir;
    private Path traceDir;
    private Workspace workspace;
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final List<RunStreamChunk> chunks = new CopyOnWriteArrayList<>();
    private final MockModelSession session = new MockModelSession();
    private RunLimits limits;

    @BeforeEach
    void setUp() throws IOException {
        inputDir = tempDir.resolve("input");
        outputDir = tempDir.resolve("output");
        traceDir = tempDir.resolve("traces");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(traceDir);
        workspace = new Workspace(inputDir, outputDir, traceDir);
        limits = new RunLimits(20, 40, Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    private static ModelResponse calls(ToolCall... calls) {
        return new ModelResponse("", List.of(calls), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
    }

    private static ModelResponse text(String text) {
        return new ModelResponse(text, List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
    }

    @Test
    void taskBriefModelLifecycleAndFormatting() {
        TaskBrief brief = TaskBrief.of("Analyze Q3 metrics", Map.of("baseline", "2026-08", "target", "2026-09"), List.of("comparison_report"));
        assertEquals(1, brief.goalVersion());
        assertEquals("Analyze Q3 metrics", brief.objective());
        assertTrue(brief.constraints().containsKey("baseline"));
        assertTrue(brief.deliverables().contains("comparison_report"));

        String block = brief.toPromptBlock();
        assertTrue(block.contains("[TaskBrief v1]"));
        assertTrue(block.contains("baseline: 2026-08"));
        assertTrue(block.contains("comparison_report"));

        TaskBrief revised = brief.withRevisedGoal("Analyze Q3 metrics online channel only");
        assertEquals(2, revised.goalVersion());
        assertEquals("Analyze Q3 metrics online channel only", revised.objective());
        assertTrue(revised.toPromptBlock().contains("[TaskBrief v2]"));
    }

    @Test
    void taskPlanValidationRules() {
        TaskPlan plan = TaskPlan.empty(1);
        assertEquals(0, plan.planRevision());
        assertEquals(1, plan.goalVersion());

        // 1. Revision mismatch rejected
        var res1 = plan.validateUpdate(1, 1, List.of(TaskStep.of("s1", "Step 1")), Set.of());
        assertFalse(res1.valid());
        assertEquals("REVISION_MISMATCH", res1.code());

        // 2. Goal version mismatch rejected
        var res2 = plan.validateUpdate(0, 2, List.of(TaskStep.of("s1", "Step 1")), Set.of());
        assertFalse(res2.valid());
        assertEquals("GOAL_VERSION_MISMATCH", res2.code());

        // 3. Multiple active steps rejected
        var res3 = plan.validateUpdate(0, 1, List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.ACTIVE),
                TaskStep.of("s2", "Step 2", TaskStep.Status.ACTIVE)
        ), Set.of());
        assertFalse(res3.valid());
        assertEquals("MULTIPLE_ACTIVE_STEPS", res3.code());

        // 4. DONE step without evidenceRefs rejected
        var res4 = plan.validateUpdate(0, 1, List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.DONE, List.of(), "")
        ), Set.of());
        assertFalse(res4.valid());
        assertEquals("MISSING_EVIDENCE", res4.code());

        // 5. DONE step with non-existent evidence rejected
        var res5 = plan.validateUpdate(0, 1, List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.DONE, List.of("e_unknown"), "")
        ), Set.of("e_known"));
        assertFalse(res5.valid());
        assertEquals("INVALID_EVIDENCE_REF", res5.code());

        // 6. DROPPED step without reason rejected
        var res6 = plan.validateUpdate(0, 1, List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.DROPPED, List.of(), "")
        ), Set.of());
        assertFalse(res6.valid());
        assertEquals("MISSING_DROP_REASON", res6.code());

        // 7. Valid update passes
        var res7 = plan.validateUpdate(0, 1, List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.DONE, List.of("e_known"), "Verified from input"),
                TaskStep.of("s2", "Step 2", TaskStep.Status.ACTIVE)
        ), Set.of("e_known"));
        assertTrue(res7.valid());

        TaskPlan updated = plan.withNextRevision("Finished step 1", List.of(
                TaskStep.of("s1", "Step 1", TaskStep.Status.DONE, List.of("e_known"), "Verified from input")
        ));
        assertEquals(1, updated.planRevision());
    }

    @Test
    void controlToolsBatchExclusivityRejectsMixedBatch() {
        AgentSpec spec = new AgentSpec("planned-agent", "You follow plans",
                Set.of(UpdatePlanTool.NAME, "dummyTool"), limits, (r, a) -> CompletionCheck.Result.satisfied());
        Tool dummy = new DummyTool("dummyTool");
        ToolCatalog catalog = new ToolCatalog(List.of(dummy));
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        // Mix updatePlan with dummyTool in same batch
        session.queue(calls(
                new ToolCall("call-1", UpdatePlanTool.NAME, "{\"expectedRevision\":0,\"goalVersion\":1,\"steps\":[]}"),
                new ToolCall("call-2", "dummyTool", "{}")
        ));
        session.queue(text("Finished after mixed error"));

        RunResult result = runner.run(spec, new RunRequest("Do planned work", workspace));
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());

        // Both calls in the batch must be failed with MIXED_CONTROL_BATCH
        assertEquals(2, result.snapshot().observations().size());
        assertTrue(result.snapshot().observations().get(0).outcome() instanceof ToolOutcome.Failure f1
                && "MIXED_CONTROL_BATCH".equals(f1.code()));
        assertTrue(result.snapshot().observations().get(1).outcome() instanceof ToolOutcome.Failure f2
                && "MIXED_CONTROL_BATCH".equals(f2.code()));
    }

    @Test
    void updatePlanExecutionUpdatesPlanAndEmitsStreamEvent() {
        AgentSpec spec = new AgentSpec("planned-agent", "Follow plan",
                Set.of(UpdatePlanTool.NAME), limits, (r, a) -> CompletionCheck.Result.satisfied());
        ToolCatalog catalog = new ToolCatalog(List.of());
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        String planArgs = """
                {
                  "expectedRevision": 0,
                  "goalVersion": 1,
                  "reason": "Initialize roadmap",
                  "steps": [
                    { "stepId": "s1", "title": "Inspect baseline CSV", "status": "ACTIVE" },
                    { "stepId": "s2", "title": "Generate report", "status": "PENDING" }
                  ]
                }
                """;

        session.queue(calls(new ToolCall("call-p1", UpdatePlanTool.NAME, planArgs)));
        session.queue(text("Plan initialized and executed"));

        RunResult result = runner.run(spec, new RunRequest("Execute project", workspace), chunks::add);
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());

        // Verify taskPlan in snapshot
        assertTrue(result.snapshot().taskPlan().isPresent());
        TaskPlan currentPlan = result.snapshot().taskPlan().get();
        assertEquals(1, currentPlan.planRevision());
        assertEquals("Initialize roadmap", currentPlan.reason());
        assertEquals(2, currentPlan.steps().size());
        assertEquals(TaskStep.Status.ACTIVE, currentPlan.steps().get(0).status());

        // Verify PLAN_UPDATED chunk streamed
        boolean hasPlanChunk = chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.PLAN_UPDATED
                && Integer.valueOf(1).equals(c.metadata().get("planRevision")));
        assertTrue(hasPlanChunk, "Should have streamed PLAN_UPDATED chunk");

        // Verify PLAN_UPDATED trace event
        boolean hasPlanEvent = events.stream().anyMatch(e -> e.type() == RunEvent.Type.PLAN_UPDATED);
        assertTrue(hasPlanEvent, "Should have logged PLAN_UPDATED event");
    }

    @Test
    void saveWorkNoteAndCheckDeliverableTools() throws IOException {
        Tool saveNoteTool = new SaveWorkNoteTool();
        Tool checkDeliverableTool = new CheckDeliverableTool();
        AgentSpec spec = new AgentSpec("work-agent", "Use work tools",
                Set.of(SaveWorkNoteTool.NAME, CheckDeliverableTool.NAME), limits,
                (r, a) -> CompletionCheck.Result.satisfied());
        ToolCatalog catalog = new ToolCatalog(List.of(saveNoteTool, checkDeliverableTool));
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        // Turn 1: Save work note
        session.queue(calls(
                new ToolCall("call-n1", SaveWorkNoteTool.NAME, "{\"title\":\"Hypothesis 1\",\"content\":\"Promotion caused dip\"}")
        ));
        // Turn 2: Check non-existent deliverable
        session.queue(calls(
                new ToolCall("call-c1", CheckDeliverableTool.NAME, "{\"artifactId\":\"art-nonexistent\"}")
        ));
        session.queue(text("Notes saved and checked"));

        RunResult result = runner.run(spec, new RunRequest("Do investigation", workspace));
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());

        // Verify note observation
        ToolOutcome noteOutcome = result.snapshot().observations().get(0).outcome();
        assertTrue(noteOutcome instanceof ToolOutcome.Success s && s.outputJson().contains("MODEL_DERIVED"));

        // Verify deliverable check outcome
        ToolOutcome checkOutcome = result.snapshot().observations().get(1).outcome();
        assertTrue(checkOutcome instanceof ToolOutcome.Success s && s.outputJson().contains("ARTIFACT_NOT_FOUND"));
    }

    @Test
    void goalRevisionSupersedesPendingQuestionAndUpdatesBrief() {
        AgentSpec spec = new AgentSpec("dialogue-agent", "Support dialogue",
                Set.of(ClarificationTool.NAME), limits, (r, a) -> CompletionCheck.Result.satisfied());
        ToolCatalog catalog = new ToolCatalog(List.of(new ClarificationTool()));
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        // Initial goal produces a clarification question
        session.queue(calls(
                new ToolCall("call-q1", ClarificationTool.NAME, "{\"question\":\"Which channels should we compare?\"}")
        ));

        RunResult initialResult = runner.run(spec, new RunRequest("Compare channels", workspace), chunks::add);
        assertEquals(RunStatus.WAITING_INPUT, initialResult.snapshot().status());
        assertEquals("Which channels should we compare?", initialResult.snapshot().pendingQuestion().orElseThrow().question());

        // Now user revises goal directly instead of answering the ambiguous question!
        TaskBrief revisedBrief = TaskBrief.of("Compare only online channel")
                .withRevisedGoal("Compare only online channel"); // goalVersion = 2

        session.queue(text("Adapting to online-only comparison report"));

        RunResult revisedResult = runner.reviseGoal(initialResult.snapshot().runId(), revisedBrief, chunks::add);
        assertEquals(RunStatus.COMPLETED, revisedResult.snapshot().status());
        assertEquals(2, revisedResult.snapshot().taskBrief().orElseThrow().goalVersion());
        assertEquals("Compare only online channel", revisedResult.snapshot().goal());

        // Verify QUESTION_SUPERSEDED was recorded for the pending call
        boolean supersededFound = revisedResult.snapshot().observations().stream()
                .anyMatch(obs -> obs.outcome() instanceof ToolOutcome.Failure f && "QUESTION_SUPERSEDED".equals(f.code()));
        assertTrue(supersededFound, "Clarification call should be superseded by goal revision");

        // Verify BRIEF_UPDATED stream chunk was emitted
        boolean briefStreamed = chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.BRIEF_UPDATED
                && Integer.valueOf(2).equals(c.metadata().get("goalVersion")));
        assertTrue(briefStreamed, "BRIEF_UPDATED chunk should be streamed");
    }

    @Test
    void deliverableQualityVerificationAndCorrectionFeedback() throws IOException {
        TaskBrief brief = TaskBrief.of("Generate financial comparison", List.of("comparison_report", "limitations_note"));
        AgentSpec spec = new AgentSpec("report-agent", "Generate both reports",
                Set.of("createReport"), limits, (r, a) -> CompletionCheck.Result.satisfied());

        // Tool to create report artifact
        Tool createReportTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("createReport", "Create report", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                try {
                    String name = ToolJson.parse(argumentsJson).path("name").asText("comparison_report");
                    Path file = context.workspace().outputRoot().resolve(name + ".md");
                    Files.writeString(file, "Report content for " + name);
                    context.artifactSink().accept(new Artifact(name, file, "digest123", List.of(), context.resources().goalVersion()));
                    return new ToolOutcome.Success("{\"ok\":true}");
                } catch (Exception e) {
                    return new ToolOutcome.Failure("IO_ERROR", e.getMessage(), false);
                }
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(createReportTool));
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        // Turn 1: Model generates comparison_report, then tries to complete without limitations_note
        session.queue(calls(
                new ToolCall("call-r1", "createReport", "{\"name\":\"comparison_report\"}")
        ));
        session.queue(text("Finished comparison!"));

        // Turn 2: Model receives MISSING_DELIVERABLE feedback, then generates limitations_note and completes
        session.queue(calls(
                new ToolCall("call-r2", "createReport", "{\"name\":\"limitations_note\"}")
        ));
        session.queue(text("Both reports are fully generated and delivered."));

        RunRequest request = new RunRequest("Generate financial comparison", workspace, brief);
        RunResult result = runner.run(spec, request);

        // Result should be COMPLETED after autonomous correction
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals(2, result.snapshot().artifacts().size());
        assertEquals(1, session.feedback.size());
        assertTrue(session.feedback.get(0).contains("MISSING_DELIVERABLE"));
        assertTrue(session.feedback.get(0).contains("limitations_note"));
    }

    @Test
    void staleArtifactsRejectedWhenGoalVersionRevised() throws IOException {
        TaskBrief briefV1 = TaskBrief.of("Analyze Q3 metrics", List.of("comparison_report"));
        AgentSpec spec = new AgentSpec("stale-agent", "Handle metrics",
                Set.of("createReport", ClarificationTool.NAME), limits, (r, a) -> CompletionCheck.Result.satisfied());

        Tool createReportTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("createReport", "Create report", "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                try {
                    Path file = context.workspace().outputRoot().resolve("comparison_report.md");
                    Files.writeString(file, "Content v1");
                    context.artifactSink().accept(new Artifact("comparison_report", file, "digest123", List.of(), context.resources().goalVersion()));
                    return new ToolOutcome.Success("{\"ok\":true}");
                } catch (Exception e) {
                    return new ToolOutcome.Failure("IO_ERROR", e.getMessage(), false);
                }
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(createReportTool, new ClarificationTool()));
        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> session, catalog, events::add);

        // Turn 1: Generate artifact under goalVersion=1, wait
        session.queue(calls(
                new ToolCall("call-r1", "createReport", "{\"name\":\"comparison_report\"}")
        ));
        session.queue(calls(
                new ToolCall("call-q1", ClarificationTool.NAME, "{\"question\":\"Confirm baseline range?\"}")
        ));

        RunResult r1 = runner.run(spec, new RunRequest("Analyze Q3 metrics", workspace, briefV1));
        assertEquals(RunStatus.WAITING_INPUT, r1.snapshot().status());

        // Revise to goalVersion=2
        TaskBrief briefV2 = briefV1.withRevisedGoal("Analyze Q3 metrics with strict net sales"); // version 2

        // Model tries to complete claiming the v1 report is enough
        session.queue(text("The previous report already covers this!"));
        // Next turn, stops after repair limit
        session.queue(text("Still trying to reuse old report"));

        RunResult r2 = runner.reviseGoal(r1.snapshot().runId(), briefV2);
        assertEquals(RunStatus.STOPPED, r2.snapshot().status());
        assertEquals("INCOMPLETE_DELIVERABLE", r2.stopReason());
        assertTrue(session.feedback.get(0).contains("STALE_GOAL"));
    }

    private static final class DummyTool implements Tool {
        private final String name;
        DummyTool(String name) { this.name = name; }
        @Override public ToolDefinition definition() {
            return new ToolDefinition(name, "Dummy", "{\"type\":\"object\",\"additionalProperties\":false}");
        }
        @Override public ToolOutcome execute(String argumentsJson, ToolContext context) {
            return new ToolOutcome.Success("{\"ok\":true}");
        }
    }

    private static final class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        final List<String> feedback = new ArrayList<>();
        final List<String> userMessages = new ArrayList<>();

        void queue(ModelResponse response) {
            responses.add(response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("MockModelSession has no more queued responses");
            }
            return responses.poll();
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> listener) {
            ModelResponse response = nextResponse(timeout);
            if (listener != null && response.text() != null && !response.text().isBlank()) {
                listener.accept(ModelStreamChunk.text(response.text()));
            }
            return response;
        }

        @Override public void appendToolResult(String callId, ToolOutcome outcome) {}

        @Override
        public void appendRuntimeFeedback(String feedbackJson) {
            feedback.add(feedbackJson);
        }

        @Override
        public void appendUserMessage(String message) {
            userMessages.add(message);
        }

        @Override public long estimatedInputTokens() { return 100; }
        @Override public boolean compactContext(long targetBudget) { return false; }
        @Override public void close() {}
    }
}
