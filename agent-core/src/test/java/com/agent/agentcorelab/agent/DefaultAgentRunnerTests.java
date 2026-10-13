package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAgentRunnerTests {
    @TempDir
    Path root;
    private Workspace workspace;
    private Script session;
    private AtomicInteger executions;
    private AtomicLong time;
    private List<RunEvent> events;
    private DefaultAgentRunner runner;
    private RunLimits limits;
    private CompletionCheck completion;

    @BeforeEach
    void setUp() throws IOException {
        workspace = new Workspace(Files.createDirectory(root.resolve("input")), root.resolve("output"), root.resolve("traces"));
        session = new Script();
        executions = new AtomicInteger();
        time = new AtomicLong();
        events = new ArrayList<>();
        limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
        completion = (run, answer) -> new CompletionCheck.Result(true, "");
        runner = runner(tool("count", false));
    }

    @Test
    void toolsExecuteOnceAndResultsArePairedBeforeNextModelRequest() {
        session.add(calls(new ToolCall("a", "count", "{}"), new ToolCall("b", "count", "{\"value\":1}")));
        session.steps.add(() -> {
            assertEquals(List.of("a", "b"), session.results.stream().map(Result::callId).toList());
            assertEquals(2, executions.get());
            return text("finished");
        });
        RunResult result = run();
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals(2, result.snapshot().metrics().modelRequests());
        assertEquals(2, result.snapshot().metrics().toolCalls());
        assertTrue(session.closed);
    }

    @Test
    void assistantTextDoesNotSuppressTools() {
        session.add(new ModelResponse("already done", List.of(new ToolCall("a", "count", "{}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        session.add(text("really done"));
        assertEquals("really done", run().message());
        assertEquals(1, executions.get());
    }

    @Test
    void thirdCanonicalIdenticalRequestStopsBeforeExecution() {
        session.add(calls(new ToolCall("a", "count", "{\"value\":1}")));
        session.add(calls(new ToolCall("b", "count", "{ \"value\" : 1 }")));
        session.add(calls(new ToolCall("c", "count", "{\"value\":1}")));
        assertEquals("NO_PROGRESS", run().stopReason());
        assertEquals(2, executions.get());
    }

    @Test
    void unknownAndInvalidToolsAreObservedWithoutInvocation() {
        session.add(calls(new ToolCall("a", "forbidden", "{}"),
                new ToolCall("b", "count", "{\"extra\":1}"), new ToolCall("c", "count", "{}")));
        session.add(text("done"));
        RunResult result = run();
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals("UNKNOWN_TOOL", ((ToolOutcome.Failure) session.results.get(0).outcome()).code());
        assertEquals("INVALID_ARGUMENT", ((ToolOutcome.Failure) session.results.get(1).outcome()).code());
        assertEquals(1, executions.get());
    }

    @Test
    void clarificationResumesOriginalRunAndExcludesWaitingTime() {
        session.steps.add(() -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            return calls(new ToolCall("question", ClarificationTool.NAME, "{\"question\":\"Which period?\"}"));
        });
        session.steps.add(() -> {
            time.addAndGet(Duration.ofSeconds(3).toNanos());
            return text("confirmed");
        });
        RunResult waiting = run();
        assertEquals(RunStatus.WAITING_INPUT, waiting.snapshot().status());
        assertTrue(session.results.isEmpty());
        assertFalse(session.closed);
        assertThrows(IllegalStateException.class, this::run);
        time.addAndGet(Duration.ofDays(1).toNanos());
        RunResult result = runner.resume(waiting.snapshot().runId(), "September");
        assertEquals(waiting.snapshot().runId(), result.snapshot().runId());
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals(Duration.ofSeconds(5), result.snapshot().metrics().activeTime());
        assertEquals(Duration.ofSeconds(5), result.snapshot().metrics().modelTime());
        assertEquals(Duration.ZERO, result.snapshot().metrics().toolTime());
        assertEquals(Duration.ofDays(1), result.snapshot().metrics().waitingTime());
        assertEquals(2, result.snapshot().metrics().modelRequests());
        assertEquals(1, result.snapshot().metrics().toolCalls());
        assertEquals("question", session.results.getFirst().callId());
        assertTrue(((ToolOutcome.Success) session.results.getFirst().outcome()).outputJson().contains("current_user"));
        assertThrows(IllegalStateException.class, () -> runner.resume(result.snapshot().runId(), "again"));
    }

    @Test
    void clarificationDoesNotResetModelBudget() {
        limits = new RunLimits(1, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
        session.add(calls(new ToolCall("question", ClarificationTool.NAME, "{\"question\":\"Which period?\"}")));
        RunResult waiting = run();
        assertEquals("MAX_MODEL_REQUESTS", runner.resume(waiting.snapshot().runId(), "September").stopReason());
        assertEquals(1, session.requests);
    }

    @Test
    void mixedControlBatchExecutesNothingAndPairsEveryResult() {
        session.add(calls(new ToolCall("a", "count", "{}"),
                new ToolCall("q", ClarificationTool.NAME, "{\"question\":\"Which?\"}")));
        session.add(text("done"));
        assertEquals(RunStatus.COMPLETED, run().snapshot().status());
        assertEquals(0, executions.get());
        assertEquals(2, session.results.size());
        session.results.forEach(result -> assertEquals("MIXED_CONTROL_BATCH", ((ToolOutcome.Failure) result.outcome()).code()));
    }

    @Test
    void entireOversizedBatchIsRejected() {
        limits = new RunLimits(16, 1, Duration.ofSeconds(60), Duration.ofSeconds(300));
        session.add(calls(new ToolCall("a", "count", "{}"), new ToolCall("b", "count", "{}")));
        assertEquals("MAX_TOOL_CALLS", run().stopReason());
        assertEquals(0, executions.get());
        assertTrue(session.results.isEmpty());
    }

    @Test
    void cancellationRejectsLateToolsAndKeepsExecutionSlotUntilWorkerExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<UUID> id = new AtomicReference<>();
        runner = new DefaultAgentRunner(gateway(), catalog(tool("count", false)), event -> {
            if (event.type() == RunEvent.Type.RUN_STARTED) {
                id.set(event.runId());
            }
        }, time::get);
        session.steps.add(() -> {
            entered.countDown();
            try {
                assertTrue(proceed.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            return calls(new ToolCall("a", "count", "{}"));
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(this::run);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                runner.cancel(id.get());
                assertThrows(IllegalStateException.class, this::run);
            } finally {
                proceed.countDown();
            }
            assertEquals(RunStatus.CANCELLED, result.get(5, TimeUnit.SECONDS).snapshot().status());
            assertEquals(0, executions.get());
        }
    }

    @Test
    void cancellationWhileWaitingIsTerminal() {
        session.add(calls(new ToolCall("q", ClarificationTool.NAME, "{\"question\":\"Which?\"}")));
        UUID id = run().snapshot().runId();
        runner.cancel(id);
        assertEquals(RunStatus.CANCELLED, runner.snapshot(id).status());
        assertThrows(IllegalStateException.class, () -> runner.resume(id, "yes"));
        assertTrue(session.closed);
    }

    @Test
    void completionCheckAllowsOnlyOneRepairWithoutBudgetReset() {
        completion = (run, answer) -> new CompletionCheck.Result(false, "Required artifact is missing");
        session.add(text("done"));
        session.add(text("done again"));
        RunResult result = run();
        assertEquals("INCOMPLETE_DELIVERABLE", result.stopReason());
        assertEquals(1, session.feedback.size());
        assertEquals(2, result.snapshot().metrics().modelRequests());
    }

    @Test
    void duplicateCallIdsRejectWholeBatch() {
        session.add(calls(new ToolCall("a", "count", "{}"), new ToolCall("a", "count", "{}")));
        assertEquals("MODEL_PROTOCOL_ERROR", run().stopReason());
        assertEquals(0, executions.get());
    }

    @Test
    void missingCallIdRejectsWholeBatch() {
        session.add(calls(new ToolCall("", "count", "{}")));
        assertEquals("MODEL_PROTOCOL_ERROR", run().stopReason());
        assertEquals(0, executions.get());
    }

    @Test
    void truncatedResponseNeverExecutesPartialTools() {
        session.add(new ModelResponse("", List.of(new ToolCall("a", "count", "{}")),
                ModelResponse.FinishReason.LENGTH, Optional.empty()));
        assertEquals("MODEL_RESPONSE_TRUNCATED", run().stopReason());
        assertEquals(0, executions.get());
    }

    @Test
    void contextIncludesOutputReservationAndStopsBeforeRequest() {
        session.estimatedTokens = limits.maxContextTokens() - limits.outputTokenReserve() + 1L;
        assertEquals("CONTEXT_LIMIT", run().stopReason());
        assertEquals(0, session.requests);
    }

    @Test
    void submissionCancellationNeedsNeitherARunIdNorAnInitializedWorkspace() {
        var spec = new AgentSpec("test-agent", "Instructions", Set.of("count"), limits, completion);
        var result = runner.run(spec, new RunRequest("Inspect", workspace, () -> true));
        assertEquals(RunStatus.CANCELLED, result.snapshot().status());
        assertEquals(0, session.requests);
        assertFalse(Files.exists(workspace.outputRoot()));
        assertFalse(Files.exists(workspace.traceRoot()));
    }

    @Test
    void entryCancellationAlsoBlocksLateModelActions() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        session.steps.add(() -> {
            cancelled.set(true);
            return calls(new ToolCall("a", "count", "{}"));
        });
        var spec = new AgentSpec("test-agent", "Instructions", Set.of("count"), limits, completion);
        var result = runner.run(spec, new RunRequest("Inspect", workspace, cancelled::get));
        assertEquals(RunStatus.CANCELLED, result.snapshot().status());
        assertEquals(0, executions.get());
    }

    @Test
    void failedModelDoesNotInventUsageOrRetry() {
        session.steps.add(() -> { throw new IllegalStateException("unavailable"); });
        RunResult result = run();
        assertEquals("MODEL_FAILED", result.stopReason());
        assertTrue(result.snapshot().metrics().tokenUsage().isEmpty());
        assertEquals(1, session.requests);
    }

    @Test
    void requestTimeoutIsDifferentFromTotalTimeout() {
        session.steps.add(() -> { throw new IllegalStateException(new HttpTimeoutException("timeout")); });
        assertEquals("MODEL_TIMEOUT", run().stopReason());
    }

    @Test
    void terminalToolErrorStopsRemainingBatchAndModel() {
        runner = runner(tool("count", true));
        session.add(calls(new ToolCall("a", "count", "{}"), new ToolCall("b", "count", "{\"value\":2}")));
        assertEquals("INPUT_CHANGED", run().stopReason());
        assertEquals(1, executions.get());
        assertEquals(1, session.requests);
        assertTrue(events.stream().anyMatch(event -> event.summary().contains("NOT_EXECUTED_FAILED")));
    }

    @Test
    void lateTextCannotCompleteAfterActiveDeadline() {
        session.steps.add(() -> {
            time.addAndGet(limits.activeTimeout().toNanos());
            return text("done");
        });
        assertEquals("ACTIVE_TIME_LIMIT", run().stopReason());
    }

    @Test
    void lateQuestionCannotEnterWaitingAfterActiveDeadline() {
        session.steps.add(() -> {
            time.addAndGet(limits.activeTimeout().toNanos());
            return calls(new ToolCall("q", ClarificationTool.NAME, "{\"question\":\"Which?\"}"));
        });
        RunResult result = run();
        assertEquals("ACTIVE_TIME_LIMIT", result.stopReason());
        assertTrue(result.snapshot().pendingQuestion().isEmpty());
    }

    @Test
    void consecutiveErrorsAreBoundedEvenWithChangingArguments() {
        for (int i = 0; i < 3; i++) {
            session.add(calls(new ToolCall("id" + i, "count", "{\"unknown\":" + i + "}")));
        }
        assertEquals("TOOL_ERROR_LIMIT", run().stopReason());
        assertEquals(0, executions.get());
    }

    @Test
    void aSecondScenarioCanUseDifferentCompletionRulesWithoutChangingRunner() {
        completion = (run, answer) -> new CompletionCheck.Result(!run.observations().isEmpty(), "Need a query result");
        session.add(calls(new ToolCall("a", "count", "{}")));
        session.add(text("query result"));
        var spec = new AgentSpec("query-only", "Query the allowed tool; no report needed.", Set.of("count"), limits, completion);
        assertEquals(RunStatus.COMPLETED, runner.run(spec, new RunRequest("query", workspace)).snapshot().status());
    }

    @Test
    void eventFailurePreventsActions() {
        runner = new DefaultAgentRunner(gateway(), catalog(tool("count", false)), event -> {
            throw new UncheckedIOException(new IOException("disk unavailable"));
        }, time::get);
        RunResult result = run();
        assertEquals(RunStatus.FAILED, result.snapshot().status());
        assertEquals(0, session.requests);
        assertEquals(0, executions.get());
    }

    @Test
    void diagnosticsDoNotRecordGoalsOrToolArgumentValues() {
        session.add(calls(new ToolCall("a", "count", "{\"value\":1947831}")));
        session.add(text("done"));
        run();
        assertTrue(events.stream().noneMatch(event -> event.summary().contains("1947831")));
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1, events.get(i).sequence());
        }
    }

    @Test
    void changedInputAfterLastToolFailsBeforeCompletionOrRepair() throws IOException {
        Path input = workspace.inputRoot().resolve("source.txt");
        Files.writeString(input, "original");
        Tool counting = tool("count", false);
        runner = runner(new Tool() {
            @Override public ToolDefinition definition() { return counting.definition(); }
            @Override public ToolOutcome execute(String json, ToolContext context) {
                try {
                    context.resources().listFiles(context::checkActive);
                    return new ToolOutcome.Success("{\"ok\":true}");
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        });
        completion = (snapshot, answer) -> { throw new AssertionError("Changed input must stop before completion"); };
        session.add(calls(new ToolCall("a", "count", "{}")));
        session.steps.add(() -> {
            try {
                Files.writeString(input, "changed after last tool");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return text("done");
        });
        RunResult result = run();
        assertEquals(RunStatus.FAILED, result.snapshot().status());
        assertEquals("INPUT_CHANGED", result.stopReason());
        assertTrue(session.feedback.isEmpty());
    }

    @Test
    void cancellationDuringContextEstimationPreventsNetworkRequest() {
        session.beforeEstimate = () -> runner.cancel(runner.currentSnapshot().orElseThrow().runId());
        assertEquals(RunStatus.CANCELLED, run().snapshot().status());
        assertEquals(0, session.requests);
    }

    @Test
    void closeDuringModelStartedEventPreventsNetworkRequest() {
        runner = new DefaultAgentRunner(gateway(), catalog(tool("count", false)), event -> {
            events.add(event);
            if (event.type() == RunEvent.Type.MODEL_STARTED) runner.close();
        }, time::get);
        assertEquals(RunStatus.CANCELLED, run().snapshot().status());
        assertEquals(0, session.requests);
        assertTrue(session.closed);
        assertThrows(IllegalStateException.class, this::run);
    }

    @Test
    void threeIdenticalClarificationsInMixedBatchAllReceiveControlErrors() {
        session.add(calls(new ToolCall("a", ClarificationTool.NAME, "{\"question\":\"Which?\"}"),
                new ToolCall("b", ClarificationTool.NAME, "{\"question\":\"Which?\"}"),
                new ToolCall("c", ClarificationTool.NAME, "{\"question\":\"Which?\"}")));
        assertEquals("TOOL_ERROR_LIMIT", run().stopReason());
        assertEquals(3, session.results.size());
        session.results.forEach(result -> assertEquals("MIXED_CONTROL_BATCH",
                assertInstanceOf(ToolOutcome.Failure.class, result.outcome()).code()));
        assertEquals(0, executions.get());
    }

    @Test
    void interruptedToolAndUnexecutedRemainderAreBothRecorded() {
        Tool counting = tool("count", false);
        runner = runner(new Tool() {
            @Override public ToolDefinition definition() { return counting.definition(); }
            @Override public ToolOutcome execute(String json, ToolContext context) {
                executions.incrementAndGet();
                throw new java.util.concurrent.CancellationException();
            }
        });
        session.add(calls(new ToolCall("a", "count", "{}"), new ToolCall("b", "count", "{\"value\":2}")));
        assertEquals(RunStatus.CANCELLED, run().snapshot().status());
        assertEquals(1, executions.get());
        assertTrue(session.results.isEmpty());
        assertTrue(events.stream().anyMatch(event -> event.summary().contains("INTERRUPTED_CANCELLED")));
        assertTrue(events.stream().anyMatch(event -> event.summary().contains("NOT_EXECUTED_CANCELLED")));
    }

    @Test
    void resourceCloseFailureIsReflectedInTheOnlyFinalEvent() {
        session.beforeClose = () -> { throw new IllegalStateException("sensitive close failure"); };
        session.add(text("done"));
        RunResult result = run();
        assertEquals(RunStatus.FAILED, result.snapshot().status());
        assertEquals("RESOURCE_CLOSE_FAILED", result.stopReason());
        List<RunEvent> finished = events.stream().filter(event -> event.type() == RunEvent.Type.RUN_FINISHED).toList();
        assertEquals(1, finished.size());
        assertTrue(finished.getFirst().summary().contains("RESOURCE_CLOSE_FAILED"));
        assertFalse(finished.getFirst().summary().contains("COMPLETED"));
        assertFalse(finished.getFirst().summary().contains("sensitive"));
    }

    @Test
    void finalEventFailureWhileCancellingDoesNotEscapeOrLeaveRunningState() {
        runner = new DefaultAgentRunner(gateway(), catalog(tool("count", false)), event -> {
            if (event.type() == RunEvent.Type.RUN_FINISHED) throw new UncheckedIOException(new IOException("disk unavailable"));
        }, time::get);
        session.steps.add(() -> {
            runner.cancel(runner.currentSnapshot().orElseThrow().runId());
            return text("late answer");
        });
        RunResult result = run();
        assertEquals(RunStatus.FAILED, result.snapshot().status());
        assertEquals("STORAGE_FAILED", result.stopReason());
        assertTrue(session.closed);
    }

    @Test
    void segmentedTimingMetricsAccuratelyTrackModelToolAndApprovalWaitingTime() {
        Tool normalTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("count", "Normal timed tool", "{\"type\":\"object\"}");
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                time.addAndGet(Duration.ofSeconds(4).toNanos());
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };
        Tool highRiskTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("danger", "High risk timed tool", "{\"type\":\"object\"}", com.agent.agentcorelab.tool.RiskLevel.HIGH_RISK);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                time.addAndGet(Duration.ofSeconds(6).toNanos());
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };
        runner = new DefaultAgentRunner(gateway(), new ToolCatalog(List.of(normalTool, highRiskTool, new ClarificationTool())), events::add, time::get);

        session.steps.add(() -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            return calls(new ToolCall("c1", "count", "{}"), new ToolCall("c2", "danger", "{}"));
        });
        session.steps.add(() -> {
            time.addAndGet(Duration.ofSeconds(3).toNanos());
            return text("all done");
        });

        AgentSpec spec = new AgentSpec("test-agent", "Instructions", Set.of("count", "danger", ClarificationTool.NAME), limits, completion);
        RunResult waiting = runner.run(spec, new RunRequest("Execute pipeline", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, waiting.snapshot().status());
        assertEquals(Duration.ofSeconds(6), waiting.snapshot().metrics().activeTime());
        assertEquals(Duration.ofSeconds(2), waiting.snapshot().metrics().modelTime());
        assertEquals(Duration.ofSeconds(4), waiting.snapshot().metrics().toolTime());
        assertEquals(Duration.ZERO, waiting.snapshot().metrics().waitingTime());

        time.addAndGet(Duration.ofSeconds(10).toNanos());
        String approvalId = waiting.snapshot().pendingApproval().orElseThrow().approvalId();
        RunResult completed = runner.approve(waiting.snapshot().runId(), approvalId);

        assertEquals(RunStatus.COMPLETED, completed.snapshot().status());
        RunMetrics metrics = completed.snapshot().metrics();
        assertEquals(Duration.ofSeconds(15), metrics.activeTime());
        assertEquals(Duration.ofSeconds(5), metrics.modelTime());
        assertEquals(Duration.ofSeconds(10), metrics.toolTime());
        assertEquals(Duration.ofSeconds(10), metrics.waitingTime());

        RunEvent finalEvent = events.stream().filter(e -> e.type() == RunEvent.Type.RUN_FINISHED).findFirst().orElseThrow();
        assertTrue(finalEvent.summary().contains("\"activeMillis\":15000"));
        assertTrue(finalEvent.summary().contains("\"modelMillis\":5000"));
        assertTrue(finalEvent.summary().contains("\"toolMillis\":10000"));
        assertTrue(finalEvent.summary().contains("\"waitingMillis\":10000"));
    }

    @Test
    void reviseGoalDuringPendingHighRiskBatchSkipsRemainingToolsAndRecordsWaitingTime() {
        Tool normalTool = tool("count", false);
        Tool highRiskTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("danger", "High risk tool", "{\"type\":\"object\"}", com.agent.agentcorelab.tool.RiskLevel.HIGH_RISK);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };
        runner = new DefaultAgentRunner(gateway(), new ToolCatalog(List.of(normalTool, highRiskTool, new ClarificationTool())), events::add, time::get);

        session.steps.add(() -> calls(new ToolCall("c1", "danger", "{}"), new ToolCall("c2", "count", "{}")));
        session.steps.add(() -> text("completed revised goal"));

        AgentSpec spec = new AgentSpec("test-agent", "Instructions", Set.of("count", "danger", ClarificationTool.NAME), limits, completion);
        RunResult waiting = runner.run(spec, new RunRequest("Initial risky goal", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, waiting.snapshot().status());

        time.addAndGet(Duration.ofSeconds(7).toNanos());
        TaskBrief revised = new TaskBrief(2, "Safe alternative goal", java.util.Map.of(), List.of(), List.of());
        RunResult completed = runner.reviseGoal(waiting.snapshot().runId(), revised);

        assertEquals(RunStatus.COMPLETED, completed.snapshot().status());
        assertEquals(Duration.ofSeconds(7), completed.snapshot().metrics().waitingTime());
        assertEquals(2, session.results.size());
        assertEquals("APPROVAL_SUPERSEDED", ((ToolOutcome.Failure) session.results.get(0).outcome()).code());
        assertEquals("ACTION_SKIPPED", ((ToolOutcome.Failure) session.results.get(1).outcome()).code());
    }

    private RunResult run() {
        AgentSpec spec = new AgentSpec("test-agent", "Perform a bounded test task.",
                Set.of("count", ClarificationTool.NAME), limits, completion);
        return runner.run(spec, new RunRequest("Inspect controlled inputs", workspace));
    }

    private DefaultAgentRunner runner(Tool tool) {
        return new DefaultAgentRunner(gateway(), catalog(tool), events::add, time::get);
    }

    private ModelGateway gateway() {
        return (instructions, goal, definitions) -> session;
    }

    private ToolCatalog catalog(Tool tool) {
        return new ToolCatalog(List.of(tool, new ClarificationTool()));
    }

    private Tool tool(String name, boolean terminal) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Count test executions", """
                        {"type":"object","properties":{"value":{"type":"integer"}},"additionalProperties":false}
                        """);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                executions.incrementAndGet();
                return terminal ? new ToolOutcome.Failure("INPUT_CHANGED", "Input changed", true)
                        : new ToolOutcome.Success("{\"ok\":true,\"data\":{\"counted\":true}}");
            }
        };
    }

    private static ModelResponse calls(ToolCall... calls) {
        return new ModelResponse("", List.of(calls), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
    }

    private static ModelResponse text(String text) {
        return new ModelResponse(text, List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
    }

    private record Result(String callId, ToolOutcome outcome) {
    }

    private static final class Script implements ModelSession {
        private final ArrayDeque<Supplier<ModelResponse>> steps = new ArrayDeque<>();
        private final List<Result> results = new ArrayList<>();
        private final List<String> feedback = new ArrayList<>();
        private int requests;
        private long estimatedTokens = 100;
        private boolean closed;
        private Runnable beforeEstimate = () -> {};
        private Runnable beforeClose = () -> {};

        void add(ModelResponse response) {
            steps.add(() -> response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            assertFalse(timeout.isNegative() || timeout.isZero());
            requests++;
            return steps.removeFirst().get();
        }

        @Override
        public long estimatedInputTokens() {
            beforeEstimate.run();
            return estimatedTokens;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            results.add(new Result(callId, outcome));
        }

        @Override
        public void appendUserMessage(String message) {
        }

        @Override
        public void appendRuntimeFeedback(String value) {
            feedback.add(value);
        }

        @Override
        public void close() {
            closed = true;
            beforeClose.run();
        }
    }
}
