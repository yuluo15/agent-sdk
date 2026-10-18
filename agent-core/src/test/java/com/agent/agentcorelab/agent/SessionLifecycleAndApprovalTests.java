package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.policy.ActiveTaskPolicy;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.agent.run.RunStreamChunk;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.trace.RunEvent;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleAndApprovalTests {

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
        limits = new RunLimits(20, 40, Duration.ofSeconds(30), Duration.ofSeconds(60),
                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15));
    }

    private Tool createHighRiskTool(String name, AtomicInteger executionCounter) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Dangerous action requiring human approval",
                        """
                        {"type":"object","properties":{"target":{"type":"string"}},"required":["target"]}
                        """,
                        RiskLevel.HIGH_RISK);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                executionCounter.incrementAndGet();
                return new ToolOutcome.Success("{\"ok\":true,\"status\":\"EXECUTED\"}");
            }
        };
    }

    private static ModelResponse calls(ToolCall... calls) {
        return new ModelResponse("", List.of(calls), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
    }

    private static ModelResponse text(String text) {
        return new ModelResponse(text, List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
    }

    @Test
    void testHighRiskToolTriggersWaitingApprovalAndInterception() {
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Model attempts to call the high-risk rebootServer tool
        session.enqueue(calls(new ToolCall("call_reboot", "rebootServer", "{\"target\":\"prod-server-01\"}")));

        RunResult result = runner.run(spec, new RunRequest("Please reboot prod server", workspace), chunks::add);

        // Verify status and interception
        assertEquals(RunStatus.WAITING_APPROVAL, result.snapshot().status());
        assertEquals(0, highRiskExecs.get(), "High-risk tool must NOT execute without human approval");
        assertTrue(result.snapshot().pendingApproval().isPresent());

        ApprovalRequest approvalReq = result.snapshot().pendingApproval().get();
        assertEquals("rebootServer", approvalReq.toolName());
        assertEquals("call_reboot", approvalReq.callId());
        assertTrue(approvalReq.argumentsJson().contains("prod-server-01"));

        // Verify streaming chunk
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.WAITING_APPROVAL
                && "rebootServer".equals(c.toolName())));

        // Verify trace event
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.APPROVAL_REQUESTED
                && e.summary().contains("rebootServer")));
    }

    @Test
    void testApproveResumesAndExecutesHighRiskToolSuccessfully() {
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Step 1: Model requests rebootServer -> intercepts with WAITING_APPROVAL
        session.enqueue(calls(new ToolCall("call_reboot", "rebootServer", "{\"target\":\"prod-server-01\"}")));
        RunResult step1 = runner.run(spec, new RunRequest("Reboot server", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        String approvalId = step1.snapshot().pendingApproval().get().approvalId();

        // Step 2: Model finishes after tool execution
        session.enqueue(text("Server reboot completed successfully."));

        // User approves the operation
        RunResult step2 = runner.approve(step1.snapshot().runId(), approvalId, chunks::add);

        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(1, highRiskExecs.get(), "High-risk tool should have executed exactly once upon approval");
        assertEquals("Server reboot completed successfully.", step2.message());

        // Verify trace contains APPROVAL_RESOLVED with APPROVED decision
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.APPROVAL_RESOLVED
                && e.summary().contains("APPROVED")));
    }

    @Test
    void testRejectFeedsActionRejectionBackToModel() {
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Step 1: Model requests rebootServer -> intercepts with WAITING_APPROVAL
        session.enqueue(calls(new ToolCall("call_reboot", "rebootServer", "{\"target\":\"prod-server-01\"}")));
        RunResult step1 = runner.run(spec, new RunRequest("Reboot server", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        String approvalId = step1.snapshot().pendingApproval().get().approvalId();

        // Step 2: Model receives rejection, apologizes and finishes
        session.enqueue(text("Understood, server reboot was rejected by operator. No changes were made."));

        // User rejects the operation
        RunResult step2 = runner.reject(step1.snapshot().runId(), approvalId, "No restarts during business hours", chunks::add);

        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(0, highRiskExecs.get(), "Rejected high-risk tool must NOT execute");
        assertTrue(step2.message().contains("rejected"));

        // Verify trace contains APPROVAL_RESOLVED with REJECTED decision
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.APPROVAL_RESOLVED
                && e.summary().contains("REJECTED") && e.summary().contains("No restarts during business hours")));
    }

    @Test
    void testConversationFacadeApprovalFlow() {
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        AgentConversation conversation = new AgentConversation(runner, spec, workspace);

        // Turn 1: Request triggers high risk tool
        session.enqueue(calls(new ToolCall("c1", "rebootServer", "{\"target\":\"db-01\"}")));
        RunResult turn1 = conversation.send("Reboot database server");
        assertEquals(RunStatus.WAITING_APPROVAL, turn1.snapshot().status());
        String approvalId = turn1.snapshot().pendingApproval().get().approvalId();

        // Turn 2: User approves via conversation facade
        session.enqueue(text("Database server reboot confirmed and executed."));
        RunResult turn2 = conversation.approve(approvalId);

        assertEquals(RunStatus.COMPLETED, turn2.snapshot().status());
        assertEquals(1, highRiskExecs.get());
        assertEquals("Database server reboot confirmed and executed.", turn2.message());
    }

    @Test
    void testWaitingTimeoutExpiresIdleRun() throws InterruptedException {
        // Configure a short 50ms waiting timeout
        RunLimits shortWaitingLimits = new RunLimits(20, 40, Duration.ofSeconds(30), Duration.ofSeconds(60),
                32768, 4096, 3, 3, 16384, Duration.ofMillis(50));

        ToolCatalog catalog = new ToolCatalog(List.of(new ClarificationTool()));
        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("qa-agent", "Ask questions", Set.of(ClarificationTool.NAME),
                shortWaitingLimits, (s, a) -> CompletionCheck.Result.satisfied());

        session.enqueue(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"Which environment?\"}")));
        RunResult turn1 = runner.run(spec, new RunRequest("Deploy app", workspace));
        assertEquals(RunStatus.WAITING_INPUT, turn1.snapshot().status());

        // Wait past the 50ms waitingTimeout
        Thread.sleep(70);

        // Pruning should detect expired waiting run and transition it to STOPPED
        int pruned = runner.pruneExpiredRuns();
        assertEquals(1, pruned);

        RunSnapshot snapshot = runner.snapshot(turn1.snapshot().runId());
        assertEquals(RunStatus.STOPPED, snapshot.status());
    }

    @Test
    void testCapacityLimitEvictsOldestTerminalRuns() {
        ToolCatalog catalog = new ToolCatalog(List.of(new ClarificationTool()));
        // Runner with capacity limit of 3
        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add, 3);
        AgentSpec spec = new AgentSpec("qa-agent", "Simple QA", Set.of(ClarificationTool.NAME),
                limits, (s, a) -> CompletionCheck.Result.satisfied());

        // Run 1: completes
        session.enqueue(text("Result 1"));
        RunResult r1 = runner.run(spec, new RunRequest("Task 1", workspace));
        assertEquals(RunStatus.COMPLETED, r1.snapshot().status());

        // Run 2: completes
        session.enqueue(text("Result 2"));
        RunResult r2 = runner.run(spec, new RunRequest("Task 2", workspace));
        assertEquals(RunStatus.COMPLETED, r2.snapshot().status());

        // Run 3: completes (runner now has 3 active runs)
        session.enqueue(text("Result 3"));
        RunResult r3 = runner.run(spec, new RunRequest("Task 3", workspace));
        assertEquals(RunStatus.COMPLETED, r3.snapshot().status());
        assertEquals(3, runner.activeRunCount());

        // Run 4: starting 4th run should trigger ensureCapacity() and evict oldest terminal run
        session.enqueue(text("Result 4"));
        RunResult r4 = runner.run(spec, new RunRequest("Task 4", workspace));
        assertEquals(RunStatus.COMPLETED, r4.snapshot().status());

        // Capacity should stay bounded <= 3
        assertTrue(runner.activeRunCount() <= 3);
    }

    @Test
    void testRejectBatchClosesAllRemainingToolCallsWithModelSession() {
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        Tool statusTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("checkStatus", "Check server status", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"status\":\"HEALTHY\"}");
            }
        };
        Tool notifyTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("notifySlack", "Send slack notification", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"sent\":true}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, statusTool, notifyTool, new ClarificationTool()));
        DefaultAgentRunner runner = new DefaultAgentRunner((inst, g, defs) -> session, catalog, events::add);
        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops",
                Set.of("rebootServer", "checkStatus", "notifySlack"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        session.enableStrictProtocol(true);

        // Turn 1: Model issues a batch of 3 tool calls:
        // 1. checkStatus (executed immediately)
        // 2. rebootServer (HIGH_RISK -> paused for approval)
        // 3. notifySlack (pending in batch)
        session.enqueue(calls(
                new ToolCall("call_1", "checkStatus", "{}"),
                new ToolCall("call_2", "rebootServer", "{\"target\":\"prod-cluster\"}"),
                new ToolCall("call_3", "notifySlack", "{\"channel\":\"ops\"}")
        ));

        RunResult step1 = runner.run(spec, new RunRequest("Safely perform restart and notify", workspace), chunks::add);

        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        assertEquals(0, highRiskExecs.get());
        assertTrue(step1.snapshot().pendingApproval().isPresent());
        ApprovalRequest approvalReq = step1.snapshot().pendingApproval().get();
        assertEquals("call_2", approvalReq.callId());
        assertEquals("rebootServer", approvalReq.toolName());

        // Step 2: Next response from model after rejections are fed back
        session.enqueue(text("Aborted remaining maintenance operations due to user rejection."));

        // User rejects the high-risk tool call
        RunResult step2 = runner.reject(step1.snapshot().runId(), approvalReq.approvalId(), "Maintenance window expired", chunks::add);

        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(0, highRiskExecs.get(), "High-risk tool must never execute upon rejection");
        assertEquals("Aborted remaining maintenance operations due to user rejection.", step2.message());

        // Verify that all 3 tool calls were registered in the session
        assertTrue(session.recordedOutcomes().containsKey("call_1"), "call_1 should have succeeded");
        assertTrue(session.recordedOutcomes().get("call_1") instanceof ToolOutcome.Success);

        assertTrue(session.recordedOutcomes().containsKey("call_2"), "call_2 should have ACTION_REJECTED");
        ToolOutcome outcome2 = session.recordedOutcomes().get("call_2");
        assertInstanceOf(ToolOutcome.Failure.class, outcome2);
        assertEquals("ACTION_REJECTED", ((ToolOutcome.Failure) outcome2).code());
        assertTrue(((ToolOutcome.Failure) outcome2).message().contains("Maintenance window expired"));

        assertTrue(session.recordedOutcomes().containsKey("call_3"), "call_3 should have ACTION_SKIPPED");
        ToolOutcome outcome3 = session.recordedOutcomes().get("call_3");
        assertInstanceOf(ToolOutcome.Failure.class, outcome3);
        assertEquals("ACTION_SKIPPED", ((ToolOutcome.Failure) outcome3).code());
        assertTrue(((ToolOutcome.Failure) outcome3).message().contains("preceding action 'rebootServer' in batch was rejected"));

        // Verify stream chunks contain toolFinished for call_2 and call_3
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TOOL_FINISHED
                && "call_2".equals(c.callId()) && "ACTION_REJECTED".equals(c.content())));
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TOOL_FINISHED
                && "call_3".equals(c.callId()) && "ACTION_SKIPPED".equals(c.content())));

        // Verify trace events contain toolFinished for call_3
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.TOOL_FINISHED
                && e.summary().contains("ACTION_SKIPPED")));
    }

    private static final class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final List<String> userMessages = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        private final Map<String, ToolOutcome> recordedOutcomes = new java.util.LinkedHashMap<>();
        private final Set<String> pendingToolCalls = new java.util.LinkedHashSet<>();
        private boolean strictProtocol = false;

        void enqueue(ModelResponse response) {
            responses.add(response);
        }

        void enableStrictProtocol(boolean strict) {
            this.strictProtocol = strict;
        }

        Map<String, ToolOutcome> recordedOutcomes() {
            return recordedOutcomes;
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            return nextResponse(timeout, null);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> listener) {
            if (strictProtocol && !pendingToolCalls.isEmpty()) {
                throw new IllegalStateException("All pending tool callIds must be answered before continuing: " + pendingToolCalls);
            }
            ModelResponse response = responses.poll();
            if (response == null) {
                return new ModelResponse("Default mock completion.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            if (response.toolCalls() != null) {
                for (ToolCall tc : response.toolCalls()) {
                    pendingToolCalls.add(tc.callId());
                }
            }
            if (listener != null && response.text() != null && !response.text().isBlank()) {
                listener.accept(ModelStreamChunk.text(response.text()));
            }
            return response;
        }

        @Override
        public long estimatedInputTokens() {
            return 100;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            pendingToolCalls.remove(callId);
            toolResults.add(callId + ":" + outcome.getClass().getSimpleName());
            recordedOutcomes.put(callId, outcome);
        }

        @Override
        public void appendUserMessage(String message) {
            userMessages.add(message);
        }

        @Override
        public void appendRuntimeFeedback(String value) {
            userMessages.add(value);
        }

        @Override
        public void close() {
        }
    }
}
