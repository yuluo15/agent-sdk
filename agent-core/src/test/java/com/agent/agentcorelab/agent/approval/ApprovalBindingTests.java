package com.agent.agentcorelab.agent.approval;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.agent.run.RunStreamChunk;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.policy.AuthDecision;
import com.agent.agentcorelab.policy.OutputDecision;
import com.agent.agentcorelab.policy.ToolPolicy;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.ToolSemantics;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Task 06 - ApprovalBinding Anti-Tamper & Anti-Replay Security Tests")
class ApprovalBindingTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private RunLimits limits;
    private List<RunEvent> events;
    private List<RunStreamChunk> chunks;
    private MockModelSession modelSession;

    @BeforeEach
    void setUp() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path trace = tempDir.resolve("trace");
        Files.createDirectories(in);
        Files.createDirectories(out);
        Files.createDirectories(trace);
        workspace = new Workspace(in, out, trace);
        limits = new RunLimits(20, 40, Duration.ofSeconds(10), Duration.ofSeconds(20),
                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15));
        events = new CopyOnWriteArrayList<>();
        chunks = new CopyOnWriteArrayList<>();
        modelSession = new MockModelSession();
    }

    @Test
    @DisplayName("1. Canonical JSON hashing is order-invariant across keys and formatting")
    void testCanonicalHashingOrderInvariance() {
        String json1 = "{\"amount\":100,\"currency\":\"USD\",\"account\":\"ACC-99\"}";
        String json2 = "{\n  \"currency\": \"USD\",\n  \"account\": \"ACC-99\",\n  \"amount\": 100\n}";
        String json3 = "{\"account\":\"ACC-99\",\"amount\":100,\"currency\":\"USD\"}";

        String hash1 = ApprovalBinding.computeArgumentsDigest(json1);
        String hash2 = ApprovalBinding.computeArgumentsDigest(json2);
        String hash3 = ApprovalBinding.computeArgumentsDigest(json3);

        assertEquals(hash1, hash2, "Key order and formatting differences must produce identical canonical SHA-256");
        assertEquals(hash1, hash3, "Key order differences must produce identical canonical SHA-256");
        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 hex string must be 64 characters");
    }

    @Test
    @DisplayName("2. Parameter tampering is detected and throws APPROVAL_TAMPERED")
    void testTamperedParametersRejected() {
        String originalArgs = "{\"amount\":100,\"currency\":\"USD\"}";
        String tamperedArgs = "{\"amount\":99999,\"currency\":\"USD\"}";

        UUID approvalId = UUID.randomUUID();
        ApprovalBinding binding = new ApprovalBinding(
                approvalId,
                "tenant-01",
                "requester-01",
                "OPERATOR",
                "wireTransfer",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(originalArgs),
                "1.0",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-01")
                .userPrincipal("approver-01")
                .scope("OPERATOR")
                .build();

        ApprovalException ex = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(tamperedArgs, approver));
        assertEquals("APPROVAL_TAMPERED", ex.errorCode());
        assertTrue(ex.getMessage().contains("tampered"), "Error message should mention tampering");

        // The token should NOT have been consumed by a rejected attempt
        assertFalse(binding.isConsumed(), "Binding must remain unconsumed after tampered attempt failure");

        // Now validate with original args; should succeed
        binding.validateAndConsume(originalArgs, approver);
        assertTrue(binding.isConsumed(), "Binding must be consumed after successful validation");
    }

    @Test
    @DisplayName("3. Anti-Replay prevents reusing an already consumed approval token")
    void testAntiReplayPreventsReuse() {
        String args = "{\"amount\":50}";
        ApprovalBinding binding = new ApprovalBinding(
                UUID.randomUUID(),
                "tenant-01",
                "requester-01",
                "OPERATOR",
                "refund",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(args),
                "1.0",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-01")
                .scope("OPERATOR")
                .build();

        // 1st consumption: success
        binding.validateAndConsume(args, approver);
        assertTrue(binding.isConsumed());

        // 2nd consumption attempt (replay): rejected
        ApprovalException ex = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(args, approver));
        assertEquals("APPROVAL_REPLAY", ex.errorCode());
    }

    @Test
    @DisplayName("4. Expired approval token is rejected with APPROVAL_EXPIRED")
    void testExpiredApprovalRejected() {
        String args = "{\"amount\":50}";
        ApprovalBinding binding = new ApprovalBinding(
                UUID.randomUUID(),
                "tenant-01",
                "requester-01",
                "OPERATOR",
                "refund",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(args),
                "1.0",
                Instant.now().minusSeconds(5) // Expired 5 seconds ago
        );

        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-01")
                .scope("OPERATOR")
                .build();

        ApprovalException ex = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(args, approver));
        assertEquals("APPROVAL_EXPIRED", ex.errorCode());
        assertFalse(binding.isConsumed());
    }

    @Test
    @DisplayName("5. Approver lacking required role is rejected with APPROVAL_UNAUTHORIZED_ROLE")
    void testUnauthorizedApproverRoleRejected() {
        String args = "{\"amount\":5000}";
        ApprovalBinding binding = new ApprovalBinding(
                UUID.randomUUID(),
                "tenant-01",
                "requester-01",
                "FINANCE_DIRECTOR",
                "wireTransfer",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(args),
                "1.0",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        // Approver only has OPERATOR role
        TrustedContext underprivilegedApprover = TrustedContext.builder()
                .tenantId("tenant-01")
                .userPrincipal("junior-operator")
                .scope("OPERATOR")
                .build();

        ApprovalException ex = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(args, underprivilegedApprover));
        assertEquals("APPROVAL_UNAUTHORIZED_ROLE", ex.errorCode());
        assertFalse(binding.isConsumed());

        // Approver with FINANCE_DIRECTOR role succeeds
        TrustedContext authorizedApprover = TrustedContext.builder()
                .tenantId("tenant-01")
                .userPrincipal("director-jane")
                .scope("FINANCE_DIRECTOR")
                .build();

        binding.validateAndConsume(args, authorizedApprover);
        assertTrue(binding.isConsumed());
    }

    @Test
    @DisplayName("6. Approver from different tenant is rejected with APPROVAL_TENANT_MISMATCH")
    void testTenantMismatchRejected() {
        String args = "{\"amount\":50}";
        ApprovalBinding binding = new ApprovalBinding(
                UUID.randomUUID(),
                "tenant-us",
                "requester-01",
                "OPERATOR",
                "refund",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(args),
                "1.0",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        // Approver from tenant-eu
        TrustedContext crossTenantApprover = TrustedContext.builder()
                .tenantId("tenant-eu")
                .userPrincipal("admin-eu")
                .scope("OPERATOR")
                .build();

        ApprovalException ex = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(args, crossTenantApprover));
        assertEquals("APPROVAL_TENANT_MISMATCH", ex.errorCode());
        assertFalse(binding.isConsumed());
    }

    @Test
    @DisplayName("7. Rejection consumes token and prevents subsequent replay or approval")
    void testRejectionConsumesToken() {
        String args = "{\"amount\":50}";
        ApprovalBinding binding = new ApprovalBinding(
                UUID.randomUUID(),
                "tenant-01",
                "requester-01",
                "OPERATOR",
                "refund",
                "call-01",
                ApprovalBinding.computeArgumentsDigest(args),
                "1.0",
                Instant.now().plus(Duration.ofMinutes(10))
        );

        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-01")
                .scope("OPERATOR")
                .build();

        // Reject token
        binding.validateRejectAndConsume(approver);
        assertTrue(binding.isConsumed());

        // Subsequent attempt to approve throws APPROVAL_REPLAY
        ApprovalException ex1 = assertThrows(ApprovalException.class, () ->
                binding.validateAndConsume(args, approver));
        assertEquals("APPROVAL_REPLAY", ex1.errorCode());

        // Subsequent attempt to reject throws APPROVAL_REPLAY
        ApprovalException ex2 = assertThrows(ApprovalException.class, () ->
                binding.validateRejectAndConsume(approver));
        assertEquals("APPROVAL_REPLAY", ex2.errorCode());
    }

    @Test
    @DisplayName("8. End-to-end Runner approval workflow with authorization, anti-tamper, and execution")
    void testRunnerApprovalWorkflowWithSecurityContext() {
        AtomicInteger execCount = new AtomicInteger(0);
        Tool highRiskTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("executePayment", "High risk payment", "{\"type\":\"object\"}",
                        RiskLevel.HIGH_RISK, ToolSemantics.destructiveAction());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                execCount.incrementAndGet();
                return new ToolOutcome.Success("{\"paymentId\":\"PAY-12345\"}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(highRiskTool));
        ToolPolicy securityPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                if ("executePayment".equals(tool.name())) {
                    return AuthDecision.requireApproval("1.0", "FINANCE_OFFICER", "Payment requires Finance Officer approval");
                }
                return AuthDecision.allow();
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                return OutputDecision.allow();
            }
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("payment-agent", "Payment execution", Set.of("executePayment"),
                limits, (s, a) -> CompletionCheck.Result.satisfied(), ExecutionPolicy.strict().withToolPolicy(securityPolicy));

        // Step 1: Model requests execution of payment
        String toolArgs = "{\"amount\":1000,\"recipient\":\"supplier-x\"}";
        modelSession.enqueue(new ModelResponse("Requesting payment", List.of(
                new ToolCall("call-p1", "executePayment", toolArgs)
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        // Step 2: Model finishes after payment
        modelSession.enqueue(new ModelResponse("Payment completed", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        TrustedContext requesterCtx = TrustedContext.builder()
                .tenantId("tenant-finance")
                .userPrincipal("clerk-bob")
                .build();

        RunRequest request = new RunRequest("Execute supplier payment", workspace, requesterCtx);
        RunResult step1 = runner.run(spec, request, chunks::add);

        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        assertTrue(step1.snapshot().pendingApproval().isPresent());
        assertTrue(step1.snapshot().approvalBinding().isPresent());

        ApprovalRequest req = step1.snapshot().pendingApproval().get();
        ApprovalBinding binding = step1.snapshot().approvalBinding().get();

        assertEquals("executePayment", req.toolName());
        assertEquals("FINANCE_OFFICER", req.requiredRole());
        assertEquals("tenant-finance", binding.tenantId());
        assertEquals("clerk-bob", binding.requesterPrincipal());
        assertFalse(binding.isConsumed());

        // Attempt 1: Unauthorized approver (wrong role) is blocked
        TrustedContext unauthorizedApprover = TrustedContext.builder()
                .tenantId("tenant-finance")
                .userPrincipal("junior-operator")
                .scope("OPERATOR")
                .build();

        ApprovalException authEx = assertThrows(ApprovalException.class, () ->
                runner.approve(step1.snapshot().runId(), req.approvalId(), unauthorizedApprover, chunks::add));
        assertEquals("APPROVAL_UNAUTHORIZED_ROLE", authEx.errorCode());
        assertEquals(0, execCount.get(), "Payment must NOT execute on unauthorized attempt");

        // Attempt 2: Cross-tenant approver is blocked
        TrustedContext crossTenantApprover = TrustedContext.builder()
                .tenantId("tenant-marketing")
                .userPrincipal("director-alice")
                .scope("FINANCE_OFFICER")
                .build();

        ApprovalException tenantEx = assertThrows(ApprovalException.class, () ->
                runner.approve(step1.snapshot().runId(), req.approvalId(), crossTenantApprover, chunks::add));
        assertEquals("APPROVAL_TENANT_MISMATCH", tenantEx.errorCode());
        assertEquals(0, execCount.get(), "Payment must NOT execute on cross-tenant attempt");

        // Attempt 3: Authorized approver succeeds
        TrustedContext authorizedApprover = TrustedContext.builder()
                .tenantId("tenant-finance")
                .userPrincipal("supervisor-dan")
                .scope("FINANCE_OFFICER")
                .build();

        RunResult step2 = runner.approve(step1.snapshot().runId(), req.approvalId(), authorizedApprover, chunks::add);
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(1, execCount.get(), "Payment must execute after authorized approval");
        assertFalse(step2.snapshot().pendingApproval().isPresent());

        // Attempt 4: Replay approval attempt on already completed run is blocked
        assertThrows(IllegalStateException.class, () ->
                runner.approve(step1.snapshot().runId(), req.approvalId(), authorizedApprover, chunks::add));

        // Check events record approver details
        boolean hasApprovalResolved = events.stream().anyMatch(e ->
                e.type() == RunEvent.Type.APPROVAL_RESOLVED
                        && e.summary().contains("supervisor-dan")
                        && e.summary().contains("tenant-finance"));
        assertTrue(hasApprovalResolved, "Run events must record approver principal and tenant in APPROVAL_RESOLVED");
    }

    @Test
    @DisplayName("9. Runner rejection workflow feeds ACTION_REJECTED to model and records approver")
    void testRunnerRejectionWorkflow() {
        AtomicInteger execCount = new AtomicInteger(0);
        Tool highRiskTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("wipeDatabase", "Wipe database", "{\"type\":\"object\"}",
                        RiskLevel.HIGH_RISK, ToolSemantics.destructiveAction());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                execCount.incrementAndGet();
                return new ToolOutcome.Success("{}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(highRiskTool));
        ToolPolicy securityPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                return AuthDecision.requireApproval("1.0", "SYS_ADMIN", "Wipe requires Admin approval");
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                return OutputDecision.allow();
            }
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("admin-agent", "Database administration", Set.of("wipeDatabase"),
                limits, (s, a) -> CompletionCheck.Result.satisfied(), ExecutionPolicy.strict().withToolPolicy(securityPolicy));

        modelSession.enqueue(new ModelResponse("Wiping db", List.of(
                new ToolCall("call-w1", "wipeDatabase", "{\"db\":\"production\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        modelSession.enqueue(new ModelResponse("Abort acknowledged", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        TrustedContext requester = TrustedContext.builder()
                .tenantId("tenant-ops")
                .userPrincipal("engineer-dan")
                .build();

        RunResult step1 = runner.run(spec, new RunRequest("Wipe production DB", workspace, requester), chunks::add);
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        ApprovalRequest req = step1.snapshot().pendingApproval().orElseThrow();

        // Reject with authorized approver
        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-ops")
                .userPrincipal("lead-alice")
                .scope("SYS_ADMIN")
                .build();

        RunResult step2 = runner.reject(step1.snapshot().runId(), req.approvalId(), "Production wiping is strictly forbidden", approver, chunks::add);
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(0, execCount.get(), "Database wiping tool must NEVER execute after rejection");

        // Tool outcome recorded in model session was ACTION_REJECTED
        ToolOutcome recorded = modelSession.recordedOutcomes.get("call-w1");
        assertNotNull(recorded);
        assertTrue(recorded instanceof ToolOutcome.Failure);
        assertEquals("ACTION_REJECTED", ((ToolOutcome.Failure) recorded).code());

        // Check rejection event recorded
        boolean hasRejectionEvent = events.stream().anyMatch(e ->
                e.type() == RunEvent.Type.APPROVAL_RESOLVED
                        && e.summary().contains("REJECTED")
                        && e.summary().contains("lead-alice")
                        && e.summary().contains("Production wiping is strictly forbidden"));
        assertTrue(hasRejectionEvent, "Rejection event must capture approver and rejection reason");
    }

    private static class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        final Map<String, ToolOutcome> recordedOutcomes = new HashMap<>();

        void enqueue(ModelResponse response) {
            responses.add(response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            return nextResponse(timeout, null);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> listener) {
            ModelResponse resp = responses.poll();
            if (resp == null) {
                return new ModelResponse("Default text", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            return resp;
        }

        @Override
        public long estimatedInputTokens() {
            return 100;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            recordedOutcomes.put(callId, outcome);
        }

        @Override
        public void appendUserMessage(String message) {
        }

        @Override
        public void appendRuntimeFeedback(String feedback) {
        }

        @Override
        public void close() {
        }
    }
}
