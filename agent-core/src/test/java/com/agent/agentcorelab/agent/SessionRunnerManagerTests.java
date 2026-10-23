package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SessionRunnerManagerTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private AtomicLong clockNanos;
    private AtomicInteger runnersCreated;
    private List<DefaultAgentRunner> createdRunners;
    private AgentRunnerFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path trace = tempDir.resolve("trace");
        java.nio.file.Files.createDirectories(in);
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.createDirectories(trace);
        workspace = new Workspace(in, out, trace, true);
        clockNanos = new AtomicLong(1_000_000_000L);
        runnersCreated = new AtomicInteger(0);
        createdRunners = new ArrayList<>();

        factory = () -> {
            runnersCreated.incrementAndGet();
            DefaultAgentRunner runner = new DefaultAgentRunner(
                    (inst, goal, tools) -> new SimpleMockSession(),
                    new ToolCatalog(List.of()),
                    event -> {},
                    clockNanos::get
            );
            createdRunners.add(runner);
            return runner;
        };
    }

    @AfterEach
    void tearDown() {
        for (DefaultAgentRunner runner : createdRunners) {
            runner.close();
        }
    }

    @Test
    void getOrCreateReturnsDistinctRunnersForDifferentSessions() {
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 10, Duration.ofMinutes(30), clockNanos::get)) {
            AgentRunner runnerA1 = manager.getOrCreate("session-A");
            AgentRunner runnerA2 = manager.getOrCreate("session-A");
            AgentRunner runnerB = manager.getOrCreate("session-B");

            assertSame(runnerA1, runnerA2, "Same session ID must return identical runner instance");
            assertNotSame(runnerA1, runnerB, "Distinct session IDs must receive isolated runner instances");
            assertEquals(2, manager.sessionCount());
            assertEquals(2, runnersCreated.get());
            assertTrue(manager.contains("session-A"));
            assertTrue(manager.contains("session-B"));
            assertFalse(manager.contains("session-C"));
        }
    }

    @Test
    void concurrentSessionsExecuteIndependentlyWithoutActiveTaskConflict() {
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 10, Duration.ofMinutes(30), clockNanos::get)) {
            AgentRunner runnerA = manager.getOrCreate("user-alice");
            AgentRunner runnerB = manager.getOrCreate("user-bob");

            AgentSpec spec = new AgentSpec("worker", "test", Set.of(),
                    new RunLimits(5, 5, Duration.ofSeconds(10), Duration.ofSeconds(10)),
                    (r, a) -> new CompletionCheck.Result(true, ""));

            // Both runners can execute their runs independently without single-runner locking conflict
            RunResult resultA = runnerA.run(spec, new RunRequest("Alice task", workspace));
            RunResult resultB = runnerB.run(spec, new RunRequest("Bob task", workspace));

            assertEquals(RunStatus.COMPLETED, resultA.snapshot().status());
            assertEquals(RunStatus.COMPLETED, resultB.snapshot().status());
        }
    }

    @Test
    void lruEvictionReclaimsOldestSessionWhenCapacityReached() {
        // Capacity = 2
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 2, Duration.ofMinutes(30), clockNanos::get)) {
            AgentRunner runner1 = manager.getOrCreate("session-1");
            AgentRunner runner2 = manager.getOrCreate("session-2");
            assertEquals(2, manager.sessionCount());

            // Advance clock and access session-1 so session-2 becomes the least recently used
            clockNanos.addAndGet(Duration.ofSeconds(10).toNanos());
            manager.get("session-1");

            // Now request session-3. Since capacity is 2, session-2 (LRU) must be evicted!
            AgentRunner runner3 = manager.getOrCreate("session-3");

            assertEquals(2, manager.sessionCount());
            assertTrue(manager.contains("session-1"), "session-1 was touched recently, should remain");
            assertFalse(manager.contains("session-2"), "session-2 was oldest, should be evicted");
            assertTrue(manager.contains("session-3"), "session-3 was just created, should be present");
            assertEquals(1, manager.evictedCount());
        }
    }

    @Test
    void runningSessionIsProtectedAgainstLruEviction() {
        // Create custom runner factory where one runner reports RUNNING status
        AtomicBoolean runner1Running = new AtomicBoolean(true);
        AgentRunner mockRunner1 = new StubRunner(() -> runner1Running.get() ? RunStatus.RUNNING : RunStatus.COMPLETED);
        AgentRunner mockRunner2 = new StubRunner(() -> RunStatus.COMPLETED);
        AgentRunner mockRunner3 = new StubRunner(() -> RunStatus.COMPLETED);

        Queue<AgentRunner> queue = new ArrayDeque<>(List.of(mockRunner1, mockRunner2, mockRunner3));
        Supplier<AgentRunner> stubFactory = queue::poll;

        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(stubFactory, 2, Duration.ofMinutes(30), clockNanos::get)) {
            manager.getOrCreate("session-running"); // least recently used, but RUNNING
            clockNanos.addAndGet(Duration.ofSeconds(10).toNanos());
            manager.getOrCreate("session-idle"); // more recently used, but COMPLETED

            clockNanos.addAndGet(Duration.ofSeconds(10).toNanos());
            // Create session-3: session-running is LRU, but it is protected because it is RUNNING!
            // Therefore, session-idle must be evicted instead!
            manager.getOrCreate("session-3");

            assertTrue(manager.contains("session-running"), "RUNNING session must never be evicted by LRU");
            assertFalse(manager.contains("session-idle"), "Idle session must be evicted to yield capacity");
            assertTrue(manager.contains("session-3"));
            assertEquals(1, manager.evictedCount());
        }
    }

    @Test
    void capacityLimitThrowsIfAllSessionsRunning() {
        AgentRunner mockRunner1 = new StubRunner(() -> RunStatus.RUNNING);
        AgentRunner mockRunner2 = new StubRunner(() -> RunStatus.RUNNING);
        Queue<AgentRunner> queue = new ArrayDeque<>(List.of(mockRunner1, mockRunner2));
        Supplier<AgentRunner> stubFactory = queue::poll;

        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(stubFactory, 2, Duration.ofMinutes(30), clockNanos::get)) {
            manager.getOrCreate("session-1");
            manager.getOrCreate("session-2");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> manager.getOrCreate("session-3"));
            assertTrue(ex.getMessage().contains("capacity limit (2) reached and all active sessions are currently RUNNING"));
        }
    }

    @Test
    void evictIdleCleansUpExpiredSessions() {
        Duration idleTimeout = Duration.ofMinutes(15);
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 10, idleTimeout, clockNanos::get)) {
            manager.getOrCreate("session-1");
            manager.getOrCreate("session-2");

            // Advance time by 10 minutes (not yet idle)
            clockNanos.addAndGet(Duration.ofMinutes(10).toNanos());
            manager.get("session-2"); // touch session-2

            // Advance time by 6 minutes (total 16 minutes for session-1, 6 minutes for session-2)
            clockNanos.addAndGet(Duration.ofMinutes(6).toNanos());

            int evicted = manager.evictIdle();
            assertEquals(1, evicted);
            assertFalse(manager.contains("session-1"), "session-1 exceeded 15m idle time and must be evicted");
            assertTrue(manager.contains("session-2"), "session-2 was touched 6m ago and must remain active");
            assertEquals(1, manager.evictedCount());
            assertEquals(1, manager.sessionCount());
        }
    }

    @Test
    void removeExplicitlyClosesAndEvictsSession() {
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 10, Duration.ofMinutes(30), clockNanos::get)) {
            manager.getOrCreate("session-to-remove");
            assertTrue(manager.contains("session-to-remove"));

            Optional<AgentRunner> removed = manager.remove("session-to-remove");
            assertTrue(removed.isPresent());
            assertFalse(manager.contains("session-to-remove"));
            assertEquals(0, manager.sessionCount());
        }
    }

    @Test
    void getOrCreateConversationBindsSessionIdCorrectly() {
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(factory, 10, Duration.ofMinutes(30), clockNanos::get)) {
            AgentSpec spec = new AgentSpec("conv-agent", "chat test", Set.of(),
                    new RunLimits(5, 5, Duration.ofSeconds(10), Duration.ofSeconds(10)),
                    (r, a) -> new CompletionCheck.Result(true, ""));
            AgentConversation conversation = manager.getOrCreateConversation("chat-42", spec, workspace);

            assertNotNull(conversation);
            assertEquals("chat-42", conversation.conversationId());
            assertTrue(manager.contains("chat-42"));
        }
    }

    @Test
    void pruneExpiredRunsPrunesAcrossAllSessions() {
        AtomicInteger pruneCalled = new AtomicInteger(0);
        AgentRunner runnerMock1 = new StubRunner(() -> RunStatus.COMPLETED, pruneCalled::incrementAndGet);
        AgentRunner runnerMock2 = new StubRunner(() -> RunStatus.COMPLETED, pruneCalled::incrementAndGet);
        Queue<AgentRunner> queue = new ArrayDeque<>(List.of(runnerMock1, runnerMock2));
        try (SessionRunnerManager manager = new DefaultSessionRunnerManager(queue::poll, 10, Duration.ofMinutes(30), clockNanos::get)) {
            manager.getOrCreate("session-1");
            manager.getOrCreate("session-2");

            int totalPruned = manager.pruneExpiredRuns();
            assertEquals(3, totalPruned);
        }
    }

    private static final class SimpleMockSession implements ModelSession {
        @Override
        public ModelResponse nextResponse(Duration timeout) {
            return new ModelResponse("mock output", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
        }

        @Override public void appendToolResult(String callId, com.agent.agentcorelab.tool.ToolOutcome outcome) {}
        @Override public void appendUserMessage(String message) {}
        @Override public void appendRuntimeFeedback(String feedback) {}
        @Override public long estimatedInputTokens() { return 100; }
        @Override public boolean compactContext(long targetTokenBudget) { return false; }
        @Override public void close() {}
    }

    private static final class StubRunner implements AgentRunner, AutoCloseable {
        private final java.util.function.Supplier<RunStatus> statusSupplier;
        private final java.util.function.IntSupplier pruneSupplier;
        private boolean closed = false;

        StubRunner(java.util.function.Supplier<RunStatus> statusSupplier) {
            this(statusSupplier, () -> 0);
        }

        StubRunner(java.util.function.Supplier<RunStatus> statusSupplier, java.util.function.IntSupplier pruneSupplier) {
            this.statusSupplier = statusSupplier;
            this.pruneSupplier = pruneSupplier;
        }

        @Override
        public int pruneExpiredRuns() {
            return pruneSupplier.getAsInt();
        }

        @Override
        public RunResult run(AgentSpec agent, RunRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public RunResult resume(UUID runId, String answer) { throw new UnsupportedOperationException(); }
        @Override public void cancel(UUID runId) {}
        @Override public RunSnapshot snapshot(UUID runId) { return currentSnapshot().orElseThrow(); }

        @Override
        public Optional<RunSnapshot> currentSnapshot() {
            RunStatus status = statusSupplier.get();
            return Optional.of(new RunSnapshot(UUID.randomUUID(), "test-agent", "goal", status, List.of(), List.of(),
                    new RunMetrics(1, 0, Duration.ZERO, Optional.empty()),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
