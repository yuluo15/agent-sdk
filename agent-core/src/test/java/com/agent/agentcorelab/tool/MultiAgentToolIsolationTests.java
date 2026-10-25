package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.checkpoint.CheckpointSpi;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.trace.AuditTrailSpi;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/**
 * Task 09: Enterprise Multi-Agent Tool Isolation & Minimal-Privilege Assembly Tests.
 * <p>
 * Verifies that multiple agents sharing the same runtime environment and candidate pool
 * receive strictly isolated, minimal-privilege tool catalogs without capability leakage.
 */
class MultiAgentToolIsolationTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private RunLimits limits;
    private ToolCandidatePool sharedPool;
    private final List<RunEvent> events = new CopyOnWriteArrayList<>();

    private final AtomicInteger faqExecs = new AtomicInteger();
    private final AtomicInteger ticketExecs = new AtomicInteger();
    private final AtomicInteger refundExecs = new AtomicInteger();
    private final AtomicInteger rebootExecs = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        Path traceDir = tempDir.resolve("traces");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(traceDir);
        workspace = new Workspace(inputDir, outputDir, traceDir);
        limits = new RunLimits(20, 40, Duration.ofSeconds(10), Duration.ofSeconds(20),
                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15));

        // Shared Candidate Pool with diverse domain tools
        sharedPool = ToolCandidatePool.create();

        // 1. Customer Support Tools (Global, unconstrained role)
        sharedPool.register(CandidateTool.of(createCountingTool("faqSearch", faqExecs), "support", Set.of("query")));
        sharedPool.register(CandidateTool.of(createCountingTool("createTicket", ticketExecs), "support", Set.of("ticket")));

        // 2. Finance Tools (Requires FINANCE_OPERATOR role)
        sharedPool.register(CandidateTool.of(createCountingTool("queryBalance", new AtomicInteger()), "finance", Set.of("query"), "FINANCE_OPERATOR"));
        sharedPool.register(CandidateTool.of(createCountingTool("executeRefund", refundExecs), "finance", Set.of("refund", "high-risk"), "FINANCE_OPERATOR"));

        // 3. DevOps Tools (Restricted to internal-ops tenant)
        sharedPool.register(CandidateTool.of(createCountingTool("rebootServer", rebootExecs), "ops", Set.of("reboot"), "SYS_ADMIN", Set.of("internal-ops")));
    }

    private Tool createCountingTool(String name, AtomicInteger counter) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Action " + name, "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                counter.incrementAndGet();
                return new ToolOutcome.Success("{\"status\":\"OK\",\"tool\":\"" + name + "\"}");
            }
        };
    }

    // =========================================================================
    // 1. Physical Tool Catalog Boundary Verification
    // =========================================================================

    @Test
    void testCustomerAgent_strictlyRestrictedToCustomerTools() {
        AgentSpec customerAgent = new AgentSpec(
                "customer-support-agent",
                "Answer customer inquiries and file tickets",
                Set.of("faqSearch", "createTicket", ClarificationTool.NAME),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied()
        );

        TrustedContext callerContext = new TrustedContext("retail-tenant", "customer_user", "client-agent", Set.of("USER"));
        ToolCatalog catalog = sharedPool.assembleCatalog(customerAgent, callerContext);

        // Verification: Exactly 3 tools assembled
        List<ToolDefinition> definitions = catalog.definitions();
        assertEquals(3, definitions.size());
        assertTrue(definitions.stream().anyMatch(d -> "faqSearch".equals(d.name())));
        assertTrue(definitions.stream().anyMatch(d -> "createTicket".equals(d.name())));
        assertTrue(definitions.stream().anyMatch(d -> ClarificationTool.NAME.equals(d.name())));

        // Cross-domain tools must be physically non-existent in this catalog
        assertFalse(definitions.stream().anyMatch(d -> "executeRefund".equals(d.name())));
        assertFalse(definitions.stream().anyMatch(d -> "rebootServer".equals(d.name())));
        assertFalse(definitions.stream().anyMatch(d -> "queryBalance".equals(d.name())));

        assertThrows(IllegalArgumentException.class, () -> catalog.require("executeRefund"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("rebootServer"));
    }

    // =========================================================================
    // 2. End-to-End Runner Execution & Model Cross-Invocation Defense
    // =========================================================================

    @Test
    void testRunnerEnforcesToolIsolation_modelCannotInvokeUnscopedTool() {
        AgentSpec customerAgent = new AgentSpec(
                "customer-support-agent",
                "Help customer",
                Set.of("faqSearch", "createTicket"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied()
        );

        MockModelSession session = new MockModelSession();
        // Model attempts to hallucinate or maliciously invoke executeRefund (not in customer agent's catalog)
        session.enqueue(new ModelResponse("", List.of(
                new ToolCall("call_hack", "executeRefund", "{\"orderId\":\"12345\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        DefaultAgentRunner runner = new DefaultAgentRunner(
                (i, g, d) -> session,
                sharedPool,
                events::add
        );

        RunRequest request = new RunRequest("Please give me my refund", workspace);
        RunResult result = runner.run(customerAgent, request);

        // Tool was not in the catalog -> rejected by ToolExecutor, refund was NEVER executed
        assertEquals(0, refundExecs.get(), "executeRefund must NEVER execute under customerAgent");
        assertFalse(session.appendedResults.isEmpty(), "Model should receive tool outcome failure");
        ToolOutcome outcome = session.appendedResults.get(0);
        assertTrue(outcome instanceof ToolOutcome.Failure);
        assertEquals("UNKNOWN_TOOL", ((ToolOutcome.Failure) outcome).code());
    }

    @Test
    void testLegitimateAgentCanExecuteAuthorizedTools() {
        AgentSpec customerAgent = new AgentSpec(
                "customer-support-agent",
                "Help customer",
                Set.of("faqSearch", "createTicket"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied()
        );

        MockModelSession session = new MockModelSession();
        // Model legitimately invokes faqSearch
        session.enqueue(new ModelResponse("", List.of(
                new ToolCall("call_faq", "faqSearch", "{\"query\":\"return policy\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        session.enqueue(new ModelResponse("Return policy details: 30 days.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        DefaultAgentRunner runner = new DefaultAgentRunner(
                (i, g, d) -> session,
                sharedPool,
                events::add
        );

        RunRequest request = new RunRequest("How to return?", workspace);
        RunResult result = runner.run(customerAgent, request);

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals(1, faqExecs.get(), "Authorized tool faqSearch executed successfully");
        assertEquals("Return policy details: 30 days.", result.message());
    }

    // =========================================================================
    // 3. Multi-Agent Role & Tenant Boundary Defense
    // =========================================================================

    @Test
    void testFinanceAgent_roleGatingDefense() {
        AgentSpec financeAgent = new AgentSpec(
                "finance-agent",
                "Execute finance operations",
                Set.of("queryBalance", "executeRefund"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied()
        );

        DefaultAgentRunner runner = new DefaultAgentRunner(
                (i, g, d) -> new MockModelSession(),
                sharedPool,
                events::add
        );

        // 1. Regular user caller -> Assembly blocked with ROLE_UNAUTHORIZED
        TrustedContext regularUser = new TrustedContext("tenant-x", "regular_joe", "client-agent", Set.of("USER"));
        RunRequest req1 = new RunRequest("Refund 100", workspace, () -> false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(regularUser));

        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class, () -> runner.run(financeAgent, req1));
        assertEquals("ROLE_UNAUTHORIZED", ex.violationCode());
        assertTrue(ex.getMessage().contains("FINANCE_OPERATOR"));

        // 2. Finance operator caller -> Successfully assembles and runs
        TrustedContext financeUser = new TrustedContext("tenant-x", "alice_cfo", "client-agent", Set.of("FINANCE_OPERATOR"));
        RunRequest req2 = new RunRequest("Refund 100", workspace, () -> false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(financeUser));

        assertDoesNotThrow(() -> runner.run(financeAgent, req2));
    }

    @Test
    void testDevOpsAgent_tenantIsolationDefense() {
        AgentSpec devopsAgent = new AgentSpec(
                "devops-agent",
                "Infrastructure operations",
                Set.of("rebootServer"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied()
        );

        DefaultAgentRunner runner = new DefaultAgentRunner(
                (i, g, d) -> new MockModelSession(),
                sharedPool,
                events::add
        );

        // 1. External tenant caller -> Assembly blocked with TENANT_ISOLATION_VIOLATION
        TrustedContext externalTenant = new TrustedContext("external-customer", "bob", "client-agent", Set.of("SYS_ADMIN"));
        RunRequest req1 = new RunRequest("Reboot cluster", workspace, () -> false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(externalTenant));

        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class, () -> runner.run(devopsAgent, req1));
        assertEquals("TENANT_ISOLATION_VIOLATION", ex.violationCode());
        assertTrue(ex.getMessage().contains("external-customer"));

        // 2. Internal ops tenant caller -> Succeeded
        TrustedContext internalTenant = new TrustedContext("internal-ops", "sysadmin", "client-agent", Set.of("SYS_ADMIN"));
        RunRequest req2 = new RunRequest("Reboot cluster", workspace, () -> false, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(internalTenant));

        assertDoesNotThrow(() -> runner.run(devopsAgent, req2));
    }

    // =========================================================================
    // Mock Model Session
    // =========================================================================

    private static final class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        final List<ToolOutcome> appendedResults = new ArrayList<>();

        void enqueue(ModelResponse response) {
            responses.add(response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            ModelResponse r = responses.poll();
            if (r == null) {
                return new ModelResponse("Default mock completion.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            return r;
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> listener) {
            return nextResponse(timeout);
        }

        @Override
        public long estimatedInputTokens() {
            return 100;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            appendedResults.add(outcome);
        }

        @Override
        public void appendUserMessage(String message) {
        }

        @Override
        public void appendRuntimeFeedback(String value) {
        }

        @Override
        public void close() {
        }
    }
}
