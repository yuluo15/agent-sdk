package com.agent.agentcorelab.policy;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.agent.run.RunStreamChunk;
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
import com.agent.agentcorelab.tool.ToolSemantics;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPolicyTests {

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

    private Tool createSimpleTool(String name, RiskLevel risk, ToolOutcome outcome, AtomicInteger counter) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Description for " + name, "{\"type\":\"object\"}", risk,
                        risk == RiskLevel.HIGH_RISK ? ToolSemantics.destructiveAction() : ToolSemantics.readOnlySafe());
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                if (counter != null) {
                    counter.incrementAndGet();
                }
                return outcome;
            }
        };
    }

    @Test
    void testPermissivePolicyAllowsAll() {
        var policy = ToolPolicy.permissive();
        var defReadOnly = ToolDefinition.of("read_data", "read");
        var defHighRisk = new ToolDefinition("wipe_db", "wipe", "{}", RiskLevel.HIGH_RISK);
        var ctx = TrustedContext.anonymous();

        assertEquals(AuthDecision.DecisionType.ALLOW, policy.beforeExecute(defReadOnly, "{}", ctx).type());
        assertEquals(AuthDecision.DecisionType.ALLOW, policy.beforeExecute(defHighRisk, "{}", ctx).type());
        assertEquals(OutputDecision.DecisionType.ALLOW_OUTPUT,
                policy.afterExecute(defReadOnly, new ToolOutcome.Success("{}"), ctx).type());
    }

    @Test
    void testDefaultPolicyRequiresApprovalForHighRisk() {
        var policy = ToolPolicy.defaultPolicy();
        var defReadOnly = ToolDefinition.of("read_data", "read");
        var defHighRisk = new ToolDefinition("wipe_db", "wipe", "{}", RiskLevel.HIGH_RISK);
        var ctx = TrustedContext.anonymous();

        assertEquals(AuthDecision.DecisionType.ALLOW, policy.beforeExecute(defReadOnly, "{}", ctx).type());
        var highRiskDecision = policy.beforeExecute(defHighRisk, "{}", ctx);
        assertEquals(AuthDecision.DecisionType.REQUIRE_APPROVAL, highRiskDecision.type());
        var req = (AuthDecision.RequireApproval) highRiskDecision;
        assertEquals("1.0", req.policyVersion());
        assertEquals("OPERATOR", req.requiredRole());
    }

    @Test
    void testPolicyDenyPreExecution() {
        AtomicInteger execCount = new AtomicInteger(0);
        Tool deleteTool = createSimpleTool("deleteUser", RiskLevel.READ_ONLY, new ToolOutcome.Success("{\"ok\":true}"), execCount);
        ToolCatalog catalog = new ToolCatalog(List.of(deleteTool));

        ToolPolicy customPolicy = (tool, args, ctx) -> {
            if ("deleteUser".equals(tool.name()) && !ctx.hasScope("admin")) {
                return AuthDecision.deny("Missing required scope 'admin'", "INSUFFICIENT_SCOPE");
            }
            return AuthDecision.allow();
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("admin-agent", "Admin tasks", Set.of("deleteUser"), limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(customPolicy));

        // Queue model call to deleteUser, then finish
        modelSession.enqueue(new ModelResponse("Attempting delete",
                List.of(new ToolCall("call_1", "deleteUser", "{\"userId\":\"u_123\"}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        modelSession.enqueue(new ModelResponse("Finished after denial.",
                List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        // Context with only 'read' scope
        TrustedContext callerCtx = TrustedContext.builder()
                .tenantId("tenant_a")
                .userPrincipal("analyst_bob")
                .scopes(Set.of("read"))
                .build();

        RunResult result = runner.run(spec, new RunRequest("Delete user", workspace, callerCtx), chunks::add);

        // Verify:
        // 1. Tool was NEVER physically executed
        assertEquals(0, execCount.get(), "Tool must not execute when policy denies beforeExecute");

        // 2. Event POLICY_DENIED was recorded
        boolean hasPolicyDeniedEvent = events.stream()
                .anyMatch(e -> e.type() == RunEvent.Type.POLICY_DENIED && e.summary().contains("INSUFFICIENT_SCOPE"));
        assertTrue(hasPolicyDeniedEvent, "RunEvent POLICY_DENIED should be recorded in event trace");

        // 3. Observation fed back to model contains denial failure
        RunSnapshot snapshot = result.snapshot();
        assertEquals(1, snapshot.observations().size());
        RunSnapshot.Observation obs = snapshot.observations().getFirst();
        assertTrue(obs.outcome() instanceof ToolOutcome.Failure);
        ToolOutcome.Failure failure = (ToolOutcome.Failure) obs.outcome();
        assertEquals("INSUFFICIENT_SCOPE", failure.code());
        assertEquals("Missing required scope 'admin'", failure.message());
        assertFalse(failure.terminal());
    }

    @Test
    void testPolicyTerminalDenyHaltsRun() {
        AtomicInteger execCount = new AtomicInteger(0);
        Tool dangerousTool = createSimpleTool("exportAuditLogs", RiskLevel.READ_ONLY, new ToolOutcome.Success("{}"), execCount);
        ToolCatalog catalog = new ToolCatalog(List.of(dangerousTool));

        ToolPolicy policy = (tool, args, ctx) -> AuthDecision.terminalDeny("Security intrusion detected", "SECURITY_VIOLATION");

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("sec-agent", "Security tests", Set.of("exportAuditLogs"), limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(policy));

        modelSession.enqueue(new ModelResponse("Invoking tool",
                List.of(new ToolCall("call_1", "exportAuditLogs", "{}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Audit test", workspace), chunks::add);

        assertEquals(0, execCount.get());
        assertEquals(RunStatus.FAILED, result.snapshot().status());
        assertEquals("SECURITY_VIOLATION", result.stopReason());
    }

    @Test
    void testPolicyRequireApprovalSuspendsAndEmitsMetadata() {
        AtomicInteger refundExecs = new AtomicInteger(0);
        Tool refundTool = createSimpleTool("executeRefund", RiskLevel.HIGH_RISK,
                new ToolOutcome.Success("{\"refundTx\":\"tx_888\",\"status\":\"PAID\"}"), refundExecs);
        ToolCatalog catalog = new ToolCatalog(List.of(refundTool));

        ToolPolicy approvalPolicy = (tool, args, ctx) -> {
            if ("executeRefund".equals(tool.name())) {
                return AuthDecision.requireApproval("2.0", "FINANCE_SUPERVISOR", "Refund > 500 requires supervisor approval");
            }
            return AuthDecision.allow();
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("finance-agent", "Finance ops", Set.of("executeRefund"), limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(approvalPolicy));

        // Model attempts refund
        modelSession.enqueue(new ModelResponse("Processing refund...",
                List.of(new ToolCall("call_ref", "executeRefund", "{\"amount\":1000}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        // Model final answer after approval resumes
        modelSession.enqueue(new ModelResponse("Refund executed successfully.",
                List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        RunResult step1 = runner.run(spec, new RunRequest("Refund customer", workspace), chunks::add);

        // 1. Should be in WAITING_APPROVAL
        assertEquals(RunStatus.WAITING_APPROVAL, step1.snapshot().status());
        assertEquals(0, refundExecs.get(), "Refund must NOT execute before approval");
        assertTrue(step1.snapshot().pendingApproval().isPresent());

        ApprovalRequest req = step1.snapshot().pendingApproval().get();
        assertEquals("executeRefund", req.toolName());
        assertEquals("2.0", req.policyVersion());
        assertEquals("FINANCE_SUPERVISOR", req.requiredRole());
        assertEquals("Refund > 500 requires supervisor approval", req.description());

        // 2. Stream chunk verification
        boolean hasWaitingApprovalChunk = chunks.stream()
                .anyMatch(c -> c.type() == RunStreamChunk.Type.WAITING_APPROVAL
                        && "2.0".equals(c.metadata().get("policyVersion"))
                        && "FINANCE_SUPERVISOR".equals(c.metadata().get("requiredRole")));
        assertTrue(hasWaitingApprovalChunk, "Stream chunk should convey policyVersion and requiredRole");

        // 3. Approve and resume
        RunResult step2 = runner.approve(step1.snapshot().runId(), req.approvalId(), chunks::add);
        assertEquals(RunStatus.COMPLETED, step2.snapshot().status());
        assertEquals(1, refundExecs.get(), "Refund tool must execute after approval is granted");
    }

    @Test
    void testPolicyOutputRedactionSanitizesData() {
        String rawOutput = "{\"name\":\"Alice\",\"phone\":\"13800138000\",\"card\":\"6222021234567890\"}";
        String redactedOutput = "{\"name\":\"Alice\",\"phone\":\"138****8000\",\"card\":\"622202******7890\"}";

        Tool queryTool = createSimpleTool("getCustomerInfo", RiskLevel.READ_ONLY, new ToolOutcome.Success(rawOutput), null);
        ToolCatalog catalog = new ToolCatalog(List.of(queryTool));

        ToolPolicy redactionPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                return AuthDecision.allow();
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                if ("getCustomerInfo".equals(tool.name()) && outcome instanceof ToolOutcome.Success) {
                    return OutputDecision.redact(new ToolOutcome.Success(redactedOutput), "Masked phone and credit card PII");
                }
                return OutputDecision.allow();
            }
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("crm-agent", "CRM ops", Set.of("getCustomerInfo"), limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(redactionPolicy));

        modelSession.enqueue(new ModelResponse("Querying customer...",
                List.of(new ToolCall("c_1", "getCustomerInfo", "{\"id\":101}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        modelSession.enqueue(new ModelResponse("Done", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Get customer", workspace), chunks::add);
        assertEquals(RunStatus.COMPLETED, result.snapshot().status());

        // 1. Verify OUTPUT_REDACTED event was emitted
        boolean hasRedactedEvent = events.stream()
                .anyMatch(e -> e.type() == RunEvent.Type.OUTPUT_REDACTED && e.summary().contains("Masked phone"));
        assertTrue(hasRedactedEvent, "OUTPUT_REDACTED event must be published to trace");

        // 2. Snapshot observations contain ONLY redacted content, NEVER raw credit card
        RunSnapshot.Observation obs = result.snapshot().observations().getFirst();
        assertTrue(obs.outcome() instanceof ToolOutcome.Success);
        String obsJson = ((ToolOutcome.Success) obs.outcome()).outputJson();
        assertEquals(redactedOutput, obsJson);
        assertFalse(obsJson.contains("13800138000"), "Raw phone must not appear in snapshot observation");
        assertFalse(obsJson.contains("6222021234567890"), "Raw credit card must not appear in snapshot observation");

        // 3. Model session history received the sanitized outcome
        ToolOutcome receivedByModel = modelSession.recordedOutcomes.get("c_1");
        assertTrue(receivedByModel instanceof ToolOutcome.Success);
        assertEquals(redactedOutput, ((ToolOutcome.Success) receivedByModel).outputJson());
    }

    @Test
    void testPolicyOutputDenyBlocksEgress() {
        String confidentialData = "{\"employee\":\"Eve\",\"salary\":500000,\"ssn\":\"999-00-1111\"}";
        Tool salaryTool = createSimpleTool("getSalaryInfo", RiskLevel.READ_ONLY, new ToolOutcome.Success(confidentialData), null);
        ToolCatalog catalog = new ToolCatalog(List.of(salaryTool));

        ToolPolicy blockingPolicy = new ToolPolicy() {
            @Override
            public AuthDecision beforeExecute(ToolDefinition tool, String argumentsJson, TrustedContext context) {
                return AuthDecision.allow();
            }

            @Override
            public OutputDecision afterExecute(ToolDefinition tool, ToolOutcome outcome, TrustedContext context) {
                return OutputDecision.deny("Salary data egress blocked by compliance policy", "EGRESS_FORBIDDEN");
            }
        };

        DefaultAgentRunner runner = new DefaultAgentRunner((i, g, d) -> modelSession, catalog, events::add);
        AgentSpec spec = new AgentSpec("hr-agent", "HR ops", Set.of("getSalaryInfo"), limits,
                (s, a) -> CompletionCheck.Result.satisfied(),
                ExecutionPolicy.strict().withToolPolicy(blockingPolicy));

        modelSession.enqueue(new ModelResponse("Querying salary...",
                List.of(new ToolCall("c_sal", "getSalaryInfo", "{\"empId\":1}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        modelSession.enqueue(new ModelResponse("Done", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Check salary", workspace), chunks::add);

        // 1. Verify OUTPUT_BLOCKED event was emitted
        boolean hasBlockedEvent = events.stream()
                .anyMatch(e -> e.type() == RunEvent.Type.OUTPUT_BLOCKED && e.summary().contains("EGRESS_FORBIDDEN"));
        assertTrue(hasBlockedEvent, "OUTPUT_BLOCKED event must be emitted");

        // 2. Observation outcome is Failure, confidential data NEVER leaks into observations or model session
        RunSnapshot.Observation obs = result.snapshot().observations().getFirst();
        assertTrue(obs.outcome() instanceof ToolOutcome.Failure);
        ToolOutcome.Failure fail = (ToolOutcome.Failure) obs.outcome();
        assertEquals("EGRESS_FORBIDDEN", fail.code());
        assertEquals("Salary data egress blocked by compliance policy", fail.message());

        ToolOutcome modelGot = modelSession.recordedOutcomes.get("c_sal");
        assertTrue(modelGot instanceof ToolOutcome.Failure);
    }

    @Test
    void testToolPolicyBuilderAndComposite() {
        ToolPolicy built = ToolPolicy.builder()
                .denyTool("banned_tool", "Tool is prohibited", "BANNED_TOOL")
                .requireApproval("charge_card", "2.0", "FINANCE_MGR", "Payment needs approval")
                .onAfterExecute((tool, outcome, ctx) -> {
                    if ("export_report".equals(tool.name()) && outcome instanceof ToolOutcome.Success s) {
                        return OutputDecision.redact(new ToolOutcome.Success("{\"redacted\":true}"), "Redacted report");
                    }
                    return OutputDecision.allow();
                })
                .build();

        var ctx = TrustedContext.anonymous();

        // 1. Banned tool
        var bannedDef = ToolDefinition.of("banned_tool", "banned");
        var d1 = built.beforeExecute(bannedDef, "{}", ctx);
        assertEquals(AuthDecision.DecisionType.DENY, d1.type());
        assertEquals("BANNED_TOOL", ((AuthDecision.Deny) d1).errorCode());

        // 2. Approval tool
        var chargeDef = ToolDefinition.of("charge_card", "charge");
        var d2 = built.beforeExecute(chargeDef, "{}", ctx);
        assertEquals(AuthDecision.DecisionType.REQUIRE_APPROVAL, d2.type());
        assertEquals("FINANCE_MGR", ((AuthDecision.RequireApproval) d2).requiredRole());

        // 3. Normal tool allowed
        var normalDef = ToolDefinition.of("normal_calc", "calc");
        var d3 = built.beforeExecute(normalDef, "{}", ctx);
        assertEquals(AuthDecision.DecisionType.ALLOW, d3.type());

        // 4. Output redaction
        var exportDef = ToolDefinition.of("export_report", "export");
        var outDec = built.afterExecute(exportDef, new ToolOutcome.Success("{\"data\":\"raw\"}"), ctx);
        assertEquals(OutputDecision.DecisionType.REDACT, outDec.type());
        assertEquals("{\"redacted\":true}", ((ToolOutcome.Success) ((OutputDecision.Redact) outDec).sanitizedOutcome()).outputJson());

        // 5. Composite short-circuit
        ToolPolicy allowAll = ToolPolicy.permissive();
        ToolPolicy composite = ToolPolicy.composite(allowAll, built);
        assertEquals(AuthDecision.DecisionType.DENY, composite.beforeExecute(bannedDef, "{}", ctx).type());
    }

    private static class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final Map<String, ToolOutcome> recordedOutcomes = new HashMap<>();

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
