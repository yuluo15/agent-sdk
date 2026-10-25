package com.agent.agentcorelab.checkpoint;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.RunLimits;
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
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.trace.AuditEvent;
import com.agent.agentcorelab.trace.AuditTrailSpi;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointTests {

    @TempDir
    Path tempDir;

    private Path inputDir;
    private Path outputDir;
    private Path traceDir;
    private Workspace workspace;
    private RunLimits limits;
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();
    private final List<AuditEvent> auditEvents = new CopyOnWriteArrayList<>();
    private final AuditTrailSpi testAuditTrail = auditEvents::add;

    @BeforeEach
    void setUp() throws Exception {
        inputDir = tempDir.resolve("input");
        outputDir = tempDir.resolve("output");
        traceDir = tempDir.resolve("traces");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(traceDir);
        workspace = new Workspace(inputDir, outputDir, traceDir);
        limits = new RunLimits(20, 40, Duration.ofSeconds(10), Duration.ofSeconds(20),
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

    // =========================================================================
    // 1. Boundary Redline & Invariant Validation
    // =========================================================================

    @Test
    void testBoundaryRedline_rejectsNonWaitingStates() {
        AgentSpec spec = new AgentSpec("test-agent", "instructions", Set.of(), limits,
                (s, a) -> CompletionCheck.Result.satisfied());
        RunRequest req = new RunRequest("test goal", workspace);

        // Allowed states: WAITING_INPUT and WAITING_APPROVAL
        assertDoesNotThrow(() -> new RunCheckpoint(
                UUID.randomUUID(), 1, RunStatus.WAITING_APPROVAL, Instant.now(),
                "test-agent", spec, "test goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        ));

        assertDoesNotThrow(() -> new RunCheckpoint(
                UUID.randomUUID(), 1, RunStatus.WAITING_INPUT, Instant.now(),
                "test-agent", spec, "test goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        ));

        // Prohibited states: RUNNING, COMPLETED, FAILED, CANCELLED, STOPPED
        for (RunStatus invalidStatus : List.of(RunStatus.RUNNING, RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.CANCELLED, RunStatus.STOPPED)) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new RunCheckpoint(
                    UUID.randomUUID(), 1, invalidStatus, Instant.now(),
                    "test-agent", spec, "test goal", req, null, null, null, null,
                    null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
            ), "Should reject non-waiting status: " + invalidStatus);
            assertTrue(ex.getMessage().contains("strictly restricted to WAITING_INPUT or WAITING_APPROVAL"));
        }
    }

    @Test
    void testVersionInvariant_mustBePositive() {
        AgentSpec spec = new AgentSpec("test-agent", "instructions", Set.of(), limits,
                (s, a) -> CompletionCheck.Result.satisfied());
        RunRequest req = new RunRequest("test goal", workspace);

        assertThrows(IllegalArgumentException.class, () -> new RunCheckpoint(
                UUID.randomUUID(), 0, RunStatus.WAITING_APPROVAL, Instant.now(),
                "test-agent", spec, "test goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        ));

        assertThrows(IllegalArgumentException.class, () -> new RunCheckpoint(
                UUID.randomUUID(), -1, RunStatus.WAITING_APPROVAL, Instant.now(),
                "test-agent", spec, "test goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        ));
    }

    // =========================================================================
    // 2. InMemoryCheckpointStore Concurrency & CRUD
    // =========================================================================

    @Test
    void testInMemoryCheckpointStore_optimisticLocking() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        UUID runId = UUID.randomUUID();
        AgentSpec spec = new AgentSpec("agent-1", "instructions", Set.of(), limits,
                (s, a) -> CompletionCheck.Result.satisfied());
        RunRequest req = new RunRequest("goal", workspace);

        RunCheckpoint cp1 = new RunCheckpoint(
                runId, 1, RunStatus.WAITING_APPROVAL, Instant.now(),
                "agent-1", spec, "goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        );

        // First save at version 1 succeeds
        store.save(cp1);
        Optional<RunCheckpoint> found = store.findByRunId(runId);
        assertTrue(found.isPresent());
        assertEquals(1, found.get().version());
        assertEquals(1, store.findWaiting().size());

        // Update at version 2 succeeds
        RunCheckpoint cp2 = new RunCheckpoint(
                runId, 2, RunStatus.WAITING_APPROVAL, Instant.now(),
                "agent-1", spec, "goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        );
        store.save(cp2);
        assertEquals(2, store.findByRunId(runId).get().version());

        // Stale update at version 2 (expected version is now 3) throws CheckpointConcurrencyException
        RunCheckpoint cpStale = new RunCheckpoint(
                runId, 2, RunStatus.WAITING_APPROVAL, Instant.now(),
                "agent-1", spec, "goal", req, null, null, null, null,
                null, null, null, null, null, 0, null, null, 0, 0, "", Map.of()
        );
        CheckpointConcurrencyException ex = assertThrows(CheckpointConcurrencyException.class,
                () -> store.save(cpStale));
        assertEquals(runId, ex.runId());
        assertEquals(2, ex.currentVersion());
        assertEquals(2, ex.attemptedVersion());

        // Remove cleans up
        store.remove(runId);
        assertTrue(store.findByRunId(runId).isEmpty());
        assertTrue(store.findWaiting().isEmpty());
    }

    // =========================================================================
    // 3. Process Restart Recovery: WAITING_APPROVAL
    // =========================================================================

    @Test
    void testProcessRestartRecovery_waitingApproval_transparentRehydration() {
        InMemoryCheckpointStore sharedStore = new InMemoryCheckpointStore();
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Node 1 (Process 1): Starts the run and enters WAITING_APPROVAL
        MockModelSession sessionNode1 = new MockModelSession();
        sessionNode1.enqueue(new ModelResponse("", List.of(
                new ToolCall("call_reboot", "rebootServer", "{\"target\":\"prod-server-01\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        DefaultAgentRunner runnerNode1 = new DefaultAgentRunner(
                (inst, g, defs) -> sessionNode1,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        RunResult step1 = runnerNode1.run(spec, new RunRequest("Please reboot prod server", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        UUID runId = step1.snapshot().runId();
        String approvalId = step1.snapshot().pendingApproval().orElseThrow().approvalId();
        assertEquals(0, highRiskExecs.get(), "Tool must not execute before approval");

        // Verify checkpoint was auto-persisted in sharedStore
        Optional<RunCheckpoint> savedCp = sharedStore.findByRunId(runId);
        assertTrue(savedCp.isPresent());
        assertEquals(1, savedCp.get().version());
        assertEquals(RunStatus.WAITING_APPROVAL, savedCp.get().status());
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.CHECKPOINT_SAVED));
        assertTrue(auditEvents.stream().anyMatch(e -> e.action() == AuditEvent.Action.CHECKPOINT_SAVED));

        // Node 2 (Process 2 after restart): Fresh runner instance, completely empty in-memory runs!
        MockModelSession sessionNode2 = new MockModelSession();
        sessionNode2.enqueue(new ModelResponse("Server reboot completed successfully.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        DefaultAgentRunner runnerNode2 = new DefaultAgentRunner(
                (inst, g, defs) -> sessionNode2,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        // Before approval, verify runnerNode2 can inspect the snapshot directly from checkpoint without error
        RunSnapshot restoredSnapshot = runnerNode2.snapshot(runId);
        assertEquals(RunStatus.WAITING_APPROVAL, restoredSnapshot.status());
        assertEquals("rebootServer", restoredSnapshot.pendingApproval().orElseThrow().toolName());

        // Node 2 executes approve(runId, approvalId)
        List<RunStreamChunk> chunks = new ArrayList<>();
        RunResult step2 = runnerNode2.approve(runId, approvalId, chunks::add);

        // Verification of execution
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(1, highRiskExecs.get(), "High-risk tool must execute exactly once on approval");
        assertEquals("Server reboot completed successfully.", step2.message());

        // Verify trace events: CHECKPOINT_RESTORED and CHECKPOINT_REMOVED
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.CHECKPOINT_RESTORED
                && e.summary().contains(runId.toString())));
        assertTrue(auditEvents.stream().anyMatch(e -> e.action() == AuditEvent.Action.CHECKPOINT_RESTORED));
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.CHECKPOINT_REMOVED));

        // Verify checkpoint was cleaned up from the shared store upon completion
        assertTrue(sharedStore.findByRunId(runId).isEmpty(), "Completed run checkpoint must be cleaned up");
    }

    // =========================================================================
    // 4. Process Restart Recovery: WAITING_INPUT
    // =========================================================================

    @Test
    void testProcessRestartRecovery_waitingInput_resumeFlow() {
        InMemoryCheckpointStore sharedStore = new InMemoryCheckpointStore();
        ToolCatalog catalog = new ToolCatalog(List.of(new ClarificationTool()));

        AgentSpec spec = new AgentSpec("inquiry-agent", "Help user with questions", Set.of(ClarificationTool.NAME), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Process 1: Clarification needed -> enters WAITING_INPUT
        MockModelSession session1 = new MockModelSession();
        session1.enqueue(new ModelResponse("Clarifying", List.of(
                new ToolCall("call_clarify", ClarificationTool.NAME, "{\"question\":\"Which environment?\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        DefaultAgentRunner runner1 = new DefaultAgentRunner(
                (i, g, d) -> session1,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        RunResult step1 = runner1.run(spec, new RunRequest("Deploy app", workspace));
        assertEquals(RunStatus.WAITING_INPUT, step1.snapshot().status());
        UUID runId = step1.snapshot().runId();

        // Checkpoint verified
        assertTrue(sharedStore.findByRunId(runId).isPresent());
        assertEquals(RunStatus.WAITING_INPUT, sharedStore.findByRunId(runId).get().status());

        // Process 2: Recover from checkpoint and call resume(runId, "production")
        MockModelSession session2 = new MockModelSession();
        session2.enqueue(new ModelResponse("Deployment to production initiated.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        DefaultAgentRunner runner2 = new DefaultAgentRunner(
                (i, g, d) -> session2,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        RunResult step2 = runner2.resume(runId, "production");
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals("Deployment to production initiated.", step2.message());

        // Store cleaned up
        assertTrue(sharedStore.findByRunId(runId).isEmpty());
    }

    // =========================================================================
    // 5. Process Restart Recovery: REJECT High-Risk Tool
    // =========================================================================

    @Test
    void testProcessRestartRecovery_waitingApproval_rejectionFlow() {
        InMemoryCheckpointStore sharedStore = new InMemoryCheckpointStore();
        AtomicInteger highRiskExecs = new AtomicInteger();
        Tool rebootTool = createHighRiskTool("rebootServer", highRiskExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(rebootTool, new ClarificationTool()));

        AgentSpec spec = new AgentSpec("ops-agent", "Perform IT ops", Set.of("rebootServer"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Process 1: Enters WAITING_APPROVAL
        MockModelSession session1 = new MockModelSession();
        session1.enqueue(new ModelResponse("", List.of(
                new ToolCall("call_reboot", "rebootServer", "{\"target\":\"db-primary\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        DefaultAgentRunner runner1 = new DefaultAgentRunner(
                (i, g, d) -> session1,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        RunResult step1 = runner1.run(spec, new RunRequest("Reboot primary db", workspace));
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        UUID runId = step1.snapshot().runId();
        String approvalId = step1.snapshot().pendingApproval().orElseThrow().approvalId();

        // Process 2: Rejects the operation
        MockModelSession session2 = new MockModelSession();
        session2.enqueue(new ModelResponse("Operation cancelled due to rejection.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        DefaultAgentRunner runner2 = new DefaultAgentRunner(
                (i, g, d) -> session2,
                catalog,
                events::add,
                testAuditTrail,
                sharedStore
        );

        RunResult step2 = runner2.reject(runId, approvalId, "Database is currently busy");
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(0, highRiskExecs.get(), "Tool must never execute when rejected");
        assertEquals("Operation cancelled due to rejection.", step2.message());

        // Verify session2 recorded the rejection outcome
        assertTrue(session2.recordedOutcomes().containsKey("call_reboot"));
        ToolOutcome outcome = session2.recordedOutcomes().get("call_reboot");
        assertInstanceOf(ToolOutcome.Failure.class, outcome);
        assertEquals("ACTION_REJECTED", ((ToolOutcome.Failure) outcome).code());

        // Store cleaned up
        assertTrue(sharedStore.findByRunId(runId).isEmpty());
    }

    // =========================================================================
    // 6. Unknown Run Rejection
    // =========================================================================

    @Test
    void testUnknownRun_throwsIllegalArgumentException() {
        InMemoryCheckpointStore sharedStore = new InMemoryCheckpointStore();
        DefaultAgentRunner runner = new DefaultAgentRunner(
                (i, g, d) -> new MockModelSession(),
                new ToolCatalog(List.of()),
                events::add,
                testAuditTrail,
                sharedStore
        );

        UUID nonExistentId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> runner.snapshot(nonExistentId));
        assertThrows(IllegalArgumentException.class, () -> runner.resume(nonExistentId, "test"));
        assertThrows(IllegalArgumentException.class, () -> runner.approve(nonExistentId, "app_1"));
        assertThrows(IllegalArgumentException.class, () -> runner.reject(nonExistentId, "app_1", "reason"));
    }

    // =========================================================================
    // 7. JdbcCheckpointStore In-Memory Mock Test
    // =========================================================================

    @Test
    void testJdbcCheckpointStore_crudAndOptimisticLocking() {
        MockJdbcDataSource mockDs = new MockJdbcDataSource();
        JdbcCheckpointStore jdbcStore = new JdbcCheckpointStore(mockDs);

        // Initialize schema
        assertDoesNotThrow(jdbcStore::initSchema);
        assertTrue(mockDs.executedStatements.stream().anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS")));

        UUID runId = UUID.randomUUID();
        AgentSpec spec = new AgentSpec("jdbc-agent", "instructions", Set.of(), limits,
                (s, a) -> CompletionCheck.Result.satisfied());
        RunRequest req = new RunRequest("jdbc goal", workspace);

        RunCheckpoint cp1 = new RunCheckpoint(
                runId, 1, RunStatus.WAITING_APPROVAL, Instant.now(),
                "jdbc-agent", spec, "jdbc goal", req, null, null,
                List.of(new RunSnapshot.Observation(
                        new ToolCall("call_1", "queryDb", "{\"sql\":\"SELECT 1\"}"),
                        new ToolOutcome.Success("1")
                )),
                List.of(), null,
                new ApprovalRequest("app_1", "call_reboot", "rebootServer", "{}", "Reboot", Instant.now(), "1.0", "ADMIN"),
                null, null, List.of(), 0, null, null, 0, 0, "", Map.of("env", "prod")
        );

        // Save v1 (INSERT)
        jdbcStore.save(cp1);
        assertTrue(mockDs.table.containsKey(runId.toString()));
        assertEquals(1L, mockDs.table.get(runId.toString()).version);

        // Find by runId
        Optional<RunCheckpoint> restored1 = jdbcStore.findByRunId(runId);
        assertTrue(restored1.isPresent());
        assertEquals(runId, restored1.get().runId());
        assertEquals(1, restored1.get().version());
        assertEquals(RunStatus.WAITING_APPROVAL, restored1.get().status());
        assertEquals("prod", restored1.get().metadata().get("env"));
        assertEquals(1, restored1.get().observations().size());
        assertEquals("call_1", restored1.get().observations().get(0).call().callId());

        // Save v2 (UPDATE)
        RunCheckpoint cp2 = new RunCheckpoint(
                runId, 2, RunStatus.WAITING_APPROVAL, Instant.now(),
                "jdbc-agent", spec, "jdbc goal", req, null, null,
                restored1.get().observations(),
                List.of(), null, restored1.get().pendingApproval(),
                null, null, List.of(), 0, null, null, 0, 0, "", Map.of("env", "prod")
        );
        jdbcStore.save(cp2);
        assertEquals(2L, mockDs.table.get(runId.toString()).version);

        // Optimistic locking conflict: Attempt update with stale expected version
        RunCheckpoint cpConflict = new RunCheckpoint(
                runId, 2, RunStatus.WAITING_APPROVAL, Instant.now(),
                "jdbc-agent", spec, "jdbc goal", req, null, null,
                restored1.get().observations(),
                List.of(), null, restored1.get().pendingApproval(),
                null, null, List.of(), 0, null, null, 0, 0, "", Map.of()
        );
        assertThrows(CheckpointConcurrencyException.class, () -> jdbcStore.save(cpConflict));

        // Find waiting
        List<RunCheckpoint> waiting = jdbcStore.findWaiting();
        assertEquals(1, waiting.size());
        assertEquals(runId, waiting.get(0).runId());

        // Remove
        jdbcStore.remove(runId);
        assertFalse(mockDs.table.containsKey(runId.toString()));
        assertTrue(jdbcStore.findByRunId(runId).isEmpty());
    }

    // =========================================================================
    // Mock Session & Mock JDBC Infrastructure
    // =========================================================================

    private static final class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final List<String> userMessages = new ArrayList<>();
        private final List<String> toolResults = new ArrayList<>();
        private final Map<String, ToolOutcome> recordedOutcomes = new LinkedHashMap<>();

        void enqueue(ModelResponse response) {
            responses.add(response);
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
            ModelResponse response = responses.poll();
            if (response == null) {
                return new ModelResponse("Default mock completion.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
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

    private static final class MockRow {
        String runId;
        long version;
        String status;
        String agentId;
        String payloadJson;
    }

    private static final class MockJdbcDataSource implements DataSource {
        final Map<String, MockRow> table = new ConcurrentHashMap<>();
        final List<String> executedStatements = new CopyOnWriteArrayList<>();

        @Override
        public Connection getConnection() {
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("prepareStatement".equals(mName)) {
                            String sql = (String) args[0];
                            executedStatements.add(sql);
                            return createMockPreparedStatement(sql);
                        }
                        if ("setAutoCommit".equals(mName) || "commit".equals(mName) || "rollback".equals(mName) || "close".equals(mName)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private PreparedStatement createMockPreparedStatement(String sql) {
            final Map<Integer, Object> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if (mName.startsWith("set") && args != null && args.length >= 2) {
                            params.put((Integer) args[0], args[1]);
                            return null;
                        }
                        if ("execute".equals(mName)) {
                            return true;
                        }
                        if ("executeUpdate".equals(mName)) {
                            return executeUpdate(sql, params);
                        }
                        if ("executeQuery".equals(mName)) {
                            return executeQuery(sql, params);
                        }
                        if ("close".equals(mName)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        private int executeUpdate(String sql, Map<Integer, Object> params) {
            String lower = sql.toLowerCase();
            if (lower.startsWith("insert into")) {
                MockRow row = new MockRow();
                row.runId = (String) params.get(1);
                row.version = ((Number) params.get(2)).longValue();
                row.status = (String) params.get(3);
                row.agentId = (String) params.get(4);
                row.payloadJson = (String) params.get(5);
                table.put(row.runId, row);
                return 1;
            }
            if (lower.startsWith("update")) {
                long newVersion = ((Number) params.get(1)).longValue();
                String status = (String) params.get(2);
                String agentId = (String) params.get(3);
                String payload = (String) params.get(4);
                String runId = (String) params.get(6);
                long expectedVersion = ((Number) params.get(7)).longValue();

                MockRow existing = table.get(runId);
                if (existing != null && existing.version == expectedVersion) {
                    existing.version = newVersion;
                    existing.status = status;
                    existing.agentId = agentId;
                    existing.payloadJson = payload;
                    return 1;
                }
                return 0; // Optimistic locking mismatch
            }
            if (lower.startsWith("delete from")) {
                String runId = (String) params.get(1);
                return table.remove(runId) != null ? 1 : 0;
            }
            return 0;
        }

        private ResultSet executeQuery(String sql, Map<Integer, Object> params) {
            String lower = sql.toLowerCase();
            List<MockRow> rows = new ArrayList<>();
            if (lower.contains("select version from")) {
                String runId = (String) params.get(1);
                MockRow r = table.get(runId);
                if (r != null) rows.add(r);
            } else if (lower.contains("select payload_json from") && lower.contains("where run_id = ?")) {
                String runId = (String) params.get(1);
                MockRow r = table.get(runId);
                if (r != null) rows.add(r);
            } else if (lower.contains("select payload_json from")) {
                for (MockRow r : table.values()) {
                    if ("WAITING_INPUT".equals(r.status) || "WAITING_APPROVAL".equals(r.status)) {
                        rows.add(r);
                    }
                }
            }

            final AtomicInteger index = new AtomicInteger(-1);
            return (ResultSet) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("next".equals(mName)) {
                            return index.incrementAndGet() < rows.size();
                        }
                        if ("getLong".equals(mName)) {
                            MockRow current = rows.get(index.get());
                            return current.version;
                        }
                        if ("getString".equals(mName)) {
                            String col = (String) args[0];
                            MockRow current = rows.get(index.get());
                            if ("payload_json".equalsIgnoreCase(col)) {
                                return current.payloadJson;
                            }
                            if ("status".equalsIgnoreCase(col)) {
                                return current.status;
                            }
                            if ("agent_id".equalsIgnoreCase(col)) {
                                return current.agentId;
                            }
                            return null;
                        }
                        if ("close".equals(mName)) {
                            return null;
                        }
                        return null;
                    }
            );
        }

        @Override public Connection getConnection(String username, String password) { return getConnection(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
