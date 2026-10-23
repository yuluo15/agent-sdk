package com.agent.agentcorelab.trace;

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
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Task 07 - Append-Only AuditTrail SPI & Governance Audit Trail Tests")
class AuditTrailTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private RunLimits limits;
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
        modelSession = new MockModelSession();
    }

    @Test
    @DisplayName("1. Append-Only Invariant: events view is strictly unmodifiable and cannot be deleted or mutated")
    void testAppendOnlyContractImmutability() {
        InMemoryAuditTrail trail = AuditTrailSpi.inMemory();
        UUID runId = UUID.randomUUID();

        AuditEvent event1 = AuditEvent.builder()
                .action(AuditEvent.Action.RUN_STARTED)
                .runId(runId)
                .tenantId("tenant-finance")
                .userPrincipal("alice")
                .build();

        trail.append(event1);
        assertEquals(1, trail.size());
        assertFalse(trail.isEmpty());

        List<AuditEvent> snapshot = trail.events();
        assertEquals(1, snapshot.size());

        // Verifies no removal or clear is exposed on AuditTrailSpi / InMemoryAuditTrail
        assertThrows(UnsupportedOperationException.class, () ->
                snapshot.remove(0), "Audit event history snapshot must be strictly unmodifiable");
        assertThrows(UnsupportedOperationException.class, snapshot::clear,
                "Audit event history snapshot cannot be cleared");

        AuditEvent event2 = AuditEvent.builder()
                .action(AuditEvent.Action.RUN_FINISHED)
                .runId(runId)
                .tenantId("tenant-finance")
                .userPrincipal("alice")
                .build();
        trail.append(event2);

        assertEquals(2, trail.size());
        assertEquals(AuditEvent.Action.RUN_STARTED, trail.events().get(0).action());
        assertEquals(AuditEvent.Action.RUN_FINISHED, trail.events().get(1).action());
    }

    @Test
    @DisplayName("2. Full governance lifecycle audit chain: causal order, field completeness, and zero loss")
    void testFullLifecycleAuditChainCompleteness() {
        InMemoryAuditTrail auditTrail = AuditTrailSpi.inMemory();

        Tool wireTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("wireTransfer", "Transfer funds", "{\"type\":\"object\"}",
                        RiskLevel.HIGH_RISK, ToolSemantics.destructiveAction());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"txId\":\"TX-999\",\"balance\":8000,\"cardNum\":\"6222020011223344\"}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(wireTool));
        ToolPolicy securityPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                if ("wireTransfer".equals(tool.name())) {
                    return AuthDecision.requireApproval("1.0", "FINANCE_DIRECTOR", "High-value transfer requires Director approval");
                }
                return AuthDecision.allow();
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                if ("wireTransfer".equals(tool.name()) && outcome instanceof ToolOutcome.Success success) {
                    return OutputDecision.redact(new ToolOutcome.Success("{\"txId\":\"TX-999\",\"balance\":8000,\"cardNum\":\"622202******3344\"}"),
                            "Masked sensitive bank card numbers");
                }
                return OutputDecision.allow();
            }
        };

        AgentSpec spec = new AgentSpec(
                "finance-agent",
                "Finance execution",
                Set.of("wireTransfer"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(securityPolicy).withAuditTrail(auditTrail)
        );

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, e -> {});

        // Step 1: Model requests high-risk tool call
        String toolArgs = "{\"amount\":50000,\"beneficiary\":\"SupplierCorp\"}";
        modelSession.enqueue(new ModelResponse("Requesting wire transfer", List.of(
                new ToolCall("call-wire-1", "wireTransfer", toolArgs)
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        // Step 2: Model finishes after approval and execution
        modelSession.enqueue(new ModelResponse("Wire transfer done", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        TrustedContext requester = TrustedContext.builder()
                .traceId("trace-req-001")
                .tenantId("tenant-finance")
                .userPrincipal("clerk-bob")
                .build();

        RunRequest request = new RunRequest("Transfer 50k to supplier", workspace, requester);
        RunResult step1 = runner.run(spec, request);

        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        ApprovalRequest appReq = step1.snapshot().pendingApproval().orElseThrow();

        // Step 3: Approve with authorized context
        TrustedContext approver = TrustedContext.builder()
                .traceId("trace-appr-001")
                .tenantId("tenant-finance")
                .userPrincipal("director-carol")
                .scope("FINANCE_DIRECTOR")
                .build();

        RunResult step2 = runner.approve(step1.snapshot().runId(), appReq.approvalId(), approver);
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());

        // Verify the unbroken causal audit trail
        List<AuditEvent> events = auditTrail.events();
        assertFalse(events.isEmpty(), "Audit trail must not be empty");

        // Action sequence verification
        List<AuditEvent.Action> actions = events.stream().map(AuditEvent::action).toList();
        assertTrue(actions.contains(AuditEvent.Action.RUN_STARTED), "Must record RUN_STARTED");
        assertTrue(actions.contains(AuditEvent.Action.APPROVAL_REQUESTED), "Must record APPROVAL_REQUESTED");
        assertTrue(actions.contains(AuditEvent.Action.APPROVAL_RESOLVED), "Must record APPROVAL_RESOLVED");
        assertTrue(actions.contains(AuditEvent.Action.TOOL_STARTED), "Must record TOOL_STARTED");
        assertTrue(actions.contains(AuditEvent.Action.TOOL_EXECUTED), "Must record TOOL_EXECUTED");
        assertTrue(actions.contains(AuditEvent.Action.OUTPUT_REDACTED), "Must record OUTPUT_REDACTED");
        assertTrue(actions.contains(AuditEvent.Action.RUN_FINISHED), "Must record RUN_FINISHED");

        // Verify timestamps are non-decreasing (causal ordering)
        for (int i = 1; i < events.size(); i++) {
            assertFalse(events.get(i).timestamp().isBefore(events.get(i - 1).timestamp()),
                    "Audit events must maintain strictly non-decreasing causal order");
        }

        // Verify APPROVAL_REQUESTED event details
        AuditEvent reqAudit = auditTrail.findByAction(AuditEvent.Action.APPROVAL_REQUESTED).get(0);
        assertEquals("wireTransfer", reqAudit.toolName());
        assertEquals("call-wire-1", reqAudit.callId());
        assertEquals("tenant-finance", reqAudit.tenantId());
        assertEquals("clerk-bob", reqAudit.userPrincipal());
        assertEquals("FINANCE_DIRECTOR", reqAudit.requiredRole());
        assertEquals("1.0", reqAudit.policyVersion());
        assertEquals("High-value transfer requires Director approval", reqAudit.policyReason());
        assertNotNull(reqAudit.argumentsSha256());
        assertEquals(64, reqAudit.argumentsSha256().length(), "Must capture 64-char canonical argument hash");

        // Verify APPROVAL_RESOLVED event details
        AuditEvent resAudit = auditTrail.findByAction(AuditEvent.Action.APPROVAL_RESOLVED).get(0);
        assertEquals("wireTransfer", resAudit.toolName());
        assertEquals("APPROVED", resAudit.approvalDecision());
        assertEquals("director-carol", resAudit.approverPrincipal());
        assertEquals("tenant-finance", resAudit.approverTenantId());
        assertEquals(reqAudit.argumentsSha256(), resAudit.argumentsSha256(),
                "Arguments hash in approval resolution must match the original requested hash");

        // Verify OUTPUT_REDACTED event details
        AuditEvent redactAudit = auditTrail.findByAction(AuditEvent.Action.OUTPUT_REDACTED).get(0);
        assertEquals("wireTransfer", redactAudit.toolName());
        assertEquals("REDACT", redactAudit.policyDecision());
        assertEquals("Masked sensitive bank card numbers", redactAudit.policyReason());

        // Verify RUN_FINISHED
        AuditEvent finishAudit = auditTrail.findByAction(AuditEvent.Action.RUN_FINISHED).get(0);
        assertEquals("COMPLETED", finishAudit.metadata().get("status"));
    }

    @Test
    @DisplayName("3. Policy Deny is tracked with complete decision rationale in audit trail")
    void testPolicyDenyAuditCompleteness() {
        InMemoryAuditTrail auditTrail = AuditTrailSpi.inMemory();

        Tool forbiddenTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("dropTables", "Drop tables", "{\"type\":\"object\"}",
                        RiskLevel.HIGH_RISK, ToolSemantics.destructiveAction());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(forbiddenTool));
        ToolPolicy securityPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                return AuthDecision.terminalDeny("Direct drop table operations are strictly prohibited", "DANGEROUS_DDL");
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                return OutputDecision.allow();
            }
        };

        AgentSpec spec = new AgentSpec(
                "ddl-agent",
                "DDL agent",
                Set.of("dropTables"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(securityPolicy).withAuditTrail(auditTrail)
        );

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, e -> {});

        modelSession.enqueue(new ModelResponse("Executing drop", List.of(
                new ToolCall("call-drop-1", "dropTables", "{\"table\":\"users\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Drop user table", workspace));
        assertEquals(RunStatus.FAILED, result.snapshot().status());

        List<AuditEvent> denies = auditTrail.findByAction(AuditEvent.Action.POLICY_DENIED);
        assertEquals(1, denies.size());

        AuditEvent denyEvent = denies.get(0);
        assertEquals("dropTables", denyEvent.toolName());
        assertEquals("call-drop-1", denyEvent.callId());
        assertEquals("DENY", denyEvent.policyDecision());
        assertEquals("Direct drop table operations are strictly prohibited", denyEvent.policyReason());
        assertEquals("DANGEROUS_DDL", denyEvent.metadata().get("errorCode"));
        assertEquals("true", denyEvent.metadata().get("terminal"));
    }

    @Test
    @DisplayName("4. Approval Rejection is tracked with rejection reason and approver identity")
    void testApprovalRejectionAuditCompleteness() {
        InMemoryAuditTrail auditTrail = AuditTrailSpi.inMemory();

        Tool sensitiveTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("resetCredentials", "Reset credentials", "{\"type\":\"object\"}",
                        RiskLevel.HIGH_RISK, ToolSemantics.destructiveAction());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(sensitiveTool));
        ToolPolicy securityPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                return AuthDecision.requireApproval("1.0", "SYS_ADMIN", "Credential reset requires SysAdmin approval");
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                return OutputDecision.allow();
            }
        };

        AgentSpec spec = new AgentSpec(
                "security-agent",
                "Security ops",
                Set.of("resetCredentials"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(securityPolicy).withAuditTrail(auditTrail)
        );

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, e -> {});

        modelSession.enqueue(new ModelResponse("Resetting credentials", List.of(
                new ToolCall("call-reset-1", "resetCredentials", "{\"userId\":\"U100\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        modelSession.enqueue(new ModelResponse("Rejection handled", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        TrustedContext requester = TrustedContext.builder()
                .tenantId("tenant-ops")
                .userPrincipal("engineer-dan")
                .build();

        RunResult step1 = runner.run(spec, new RunRequest("Reset credentials", workspace, requester));
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        ApprovalRequest req = step1.snapshot().pendingApproval().orElseThrow();

        TrustedContext approver = TrustedContext.builder()
                .tenantId("tenant-ops")
                .userPrincipal("admin-charlie")
                .scope("SYS_ADMIN")
                .build();

        RunResult step2 = runner.reject(step1.snapshot().runId(), req.approvalId(), "Suspicious activity detected, reset denied", approver);
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());

        List<AuditEvent> resolved = auditTrail.findByAction(AuditEvent.Action.APPROVAL_RESOLVED);
        assertEquals(1, resolved.size());

        AuditEvent event = resolved.get(0);
        assertEquals("resetCredentials", event.toolName());
        assertEquals("REJECTED", event.approvalDecision());
        assertEquals("Suspicious activity detected, reset denied", event.policyReason());
        assertEquals("admin-charlie", event.approverPrincipal());
        assertEquals("tenant-ops", event.approverTenantId());
    }

    @Test
    @DisplayName("5. Privacy Redline Safeguard: raw LLM prompts and user bodies are NEVER recorded")
    void testPrivacyRedlineNeverLeaksRawPrompts() {
        InMemoryAuditTrail auditTrail = AuditTrailSpi.inMemory();

        Tool queryTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("safeQuery", "Safe query", "{\"type\":\"object\"}",
                        RiskLevel.READ_ONLY, ToolSemantics.readOnlySafe());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"result\":\"ok\"}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(queryTool));
        String sensitiveUserSecret = "SUPER_SECRET_TOKEN_XYZ_12345";
        String sensitiveGoal = "Analyze customer data with secret " + sensitiveUserSecret;

        AgentSpec spec = new AgentSpec(
                "privacy-agent",
                "Privacy agent instructions",
                Set.of("safeQuery"),
                limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withAuditTrail(auditTrail)
        );

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, e -> {});

        modelSession.enqueue(new ModelResponse("Answering", List.of(
                new ToolCall("call-q1", "safeQuery", "{\"secret\":\"" + sensitiveUserSecret + "\"}")
        ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        modelSession.enqueue(new ModelResponse("Finished", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        runner.run(spec, new RunRequest(sensitiveGoal, workspace));

        // Assert Privacy Redline across ALL audit events
        for (AuditEvent event : auditTrail.events()) {
            assertFalse(event.metadata().containsValue(sensitiveUserSecret),
                    "Audit event metadata must never leak raw sensitive secret");
            assertFalse(event.policyReason().contains(sensitiveUserSecret),
                    "Audit event policyReason must never leak raw sensitive secret");
            assertFalse(event.argumentsSha256().contains(sensitiveUserSecret),
                    "Audit event argumentsSha256 must only be a hash, never contain raw secret");
            // If tool arguments were recorded, verify it's a 64-char hex digest
            if (!event.argumentsSha256().isBlank()) {
                assertEquals(64, event.argumentsSha256().length(),
                        "Arguments must strictly be recorded as SHA-256 hex digest");
            }
        }
    }

    @Test
    @DisplayName("6. Composite Audit Trail dispatches to multiple sinks with failure isolation")
    void testCompositeAuditTrailFailureIsolation() {
        InMemoryAuditTrail primarySink = AuditTrailSpi.inMemory();
        AtomicBoolean failingSinkCalled = new AtomicBoolean(false);

        AuditTrailSpi failingSink = event -> {
            failingSinkCalled.set(true);
            throw new RuntimeException("Simulated external SIEM/Kafka timeout");
        };

        AuditTrailSpi composite = AuditTrailSpi.composite(failingSink, primarySink);

        AuditEvent testEvent = AuditEvent.builder()
                .action(AuditEvent.Action.RUN_STARTED)
                .runId(UUID.randomUUID())
                .tenantId("tenant-prod")
                .build();

        // Appending to composite should not throw exception even if one sink fails
        composite.append(testEvent);

        assertTrue(failingSinkCalled.get(), "Failing sink must have been invoked");
        assertEquals(1, primarySink.size(), "Primary sink must receive event despite secondary sink failure");
        assertEquals(testEvent.runId(), primarySink.events().get(0).runId());
    }

    private static class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();

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
