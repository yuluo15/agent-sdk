package com.agent.agentcorelab.agent.context;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.annotation.AgentTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedTool;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 04 - TrustedContext Immutable Security Carrier Tests")
class TrustedContextTests {

    private Workspace createWorkspace(Path tempDir, String subDir) throws IOException {
        Path base = tempDir.resolve(subDir);
        return new Workspace(
                Files.createDirectories(base.resolve("in")),
                base.resolve("out"),
                base.resolve("trace")
        );
    }

    @Test
    @DisplayName("TrustedContext defaults, null-safety, and scope evaluation")
    void testDefaultsAndScopes() {
        // Null safety
        TrustedContext ctx = new TrustedContext(null, null, null, null, null, null);
        assertEquals(TrustedContext.DEFAULT_TENANT, ctx.tenantId());
        assertEquals(TrustedContext.ANONYMOUS_USER, ctx.userPrincipal());
        assertEquals(TrustedContext.DEFAULT_AGENT, ctx.agentIdentity());
        assertTrue(ctx.scopes().isEmpty());
        assertNotNull(ctx.traceId());
        assertNotNull(ctx.requestId());

        // Scope checks
        TrustedContext scoped = TrustedContext.builder()
                .tenantId("tenant_acme")
                .userPrincipal("alice@acme.com")
                .agentIdentity("support-bot")
                .scopes("orders:read", "orders:write")
                .build();

        assertEquals("tenant_acme", scoped.tenantId());
        assertEquals("alice@acme.com", scoped.userPrincipal());
        assertEquals("support-bot", scoped.agentIdentity());
        assertTrue(scoped.hasScope("orders:read"));
        assertTrue(scoped.hasScope("orders:write"));
        assertFalse(scoped.hasScope("admin:all"));

        assertTrue(scoped.hasAnyScope("admin:all", "orders:read"));
        assertFalse(scoped.hasAnyScope("admin:all", "billing:charge"));

        assertTrue(scoped.hasAllScopes("orders:read", "orders:write"));
        assertFalse(scoped.hasAllScopes("orders:read", "billing:charge"));

        // Wildcard scope
        TrustedContext admin = TrustedContext.builder()
                .scopes("*")
                .build();
        assertTrue(admin.hasScope("anything:really"));
        assertTrue(admin.hasAnyScope("foo", "bar"));
        assertTrue(admin.hasAllScopes("foo", "bar"));

        // Immutability
        assertThrows(UnsupportedOperationException.class, () -> scoped.scopes().add("hack:scope"));
    }

    @Test
    @DisplayName("RunRequest preserves and resolves TrustedContext")
    void testRunRequestIntegration(@TempDir Path tempDir) throws IOException {
        Workspace ws = createWorkspace(tempDir, "run_req_test");

        // Default resolution
        RunRequest defaultReq = new RunRequest("test goal", ws);
        assertTrue(defaultReq.trustedContext().isEmpty());
        TrustedContext resolved = defaultReq.resolvedTrustedContext();
        assertEquals(TrustedContext.DEFAULT_TENANT, resolved.tenantId());
        assertEquals(TrustedContext.ANONYMOUS_USER, resolved.userPrincipal());

        // Explicit TrustedContext
        TrustedContext hostCtx = TrustedContext.builder()
                .tenantId("tenant_enterprise_99")
                .userPrincipal("sec_officer")
                .scopes("audit:read")
                .build();

        RunRequest customReq = new RunRequest("audit goal", ws, hostCtx);
        assertTrue(customReq.trustedContext().isPresent());
        assertSame(hostCtx, customReq.trustedContext().get());
        assertSame(hostCtx, customReq.resolvedTrustedContext());

        // withTrustedContext
        RunRequest updatedReq = defaultReq.withTrustedContext(hostCtx);
        assertSame(hostCtx, updatedReq.trustedContext().get());
    }

    @Test
    @DisplayName("ToolContext carries immutable TrustedContext")
    void testToolContextIntegration(@TempDir Path tempDir) throws IOException {
        Workspace ws = createWorkspace(tempDir, "tool_ctx_test");
        TrustedContext tc = TrustedContext.builder()
                .tenantId("t_42")
                .userPrincipal("bob")
                .scopes("data:query")
                .build();

        ToolContext context = new ToolContext(
                UUID.randomUUID(),
                ws,
                Instant.now().plusSeconds(60),
                () -> false,
                a -> {},
                null,
                tc
        );

        assertSame(tc, context.trustedContext());
        assertEquals("t_42", context.tenantId());
        assertEquals("bob", context.userPrincipal());
        assertTrue(context.hasScope("data:query"));
        assertFalse(context.hasScope("data:delete"));

        // Backward-compatible constructor defaults to anonymous
        ToolContext legacyContext = new ToolContext(
                UUID.randomUUID(),
                ws,
                Instant.now().plusSeconds(60),
                () -> false,
                a -> {},
                null
        );
        assertEquals(TrustedContext.DEFAULT_TENANT, legacyContext.tenantId());
        assertEquals(TrustedContext.ANONYMOUS_USER, legacyContext.userPrincipal());
    }

    static class SecurityToolService {
        private final AtomicReference<TrustedContext> lastInjectedContext = new AtomicReference<>();

        @AgentTool(name = "secureAudit", description = "Audits tenant database")
        public String audit(String query, TrustedContext trustedContext) {
            lastInjectedContext.set(trustedContext);
            return "audited for tenant: " + trustedContext.tenantId() + " user: " + trustedContext.userPrincipal();
        }
    }

    @Test
    @DisplayName("@AgentTool automatically injects TrustedContext without exposing it in schema")
    void testAnnotatedToolTrustedContextInjection(@TempDir Path tempDir) throws Exception {
        SecurityToolService service = new SecurityToolService();
        var method = SecurityToolService.class.getMethod("audit", String.class, TrustedContext.class);
        AnnotatedTool tool = new AnnotatedTool(service, method);

        // 1. Schema must only contain 'query', NOT 'trustedContext'
        String schema = tool.definition().inputSchema();
        assertTrue(schema.contains("query"));
        assertFalse(schema.contains("trustedContext"));

        // 2. Execution injects host-verified TrustedContext, ignoring any malicious attempts to pass tenantId
        Workspace ws = createWorkspace(tempDir, "anno_test");
        TrustedContext hostCtx = TrustedContext.builder()
                .tenantId("genuine_tenant_abc")
                .userPrincipal("carol")
                .scopes("audit:run")
                .build();

        ToolContext toolContext = new ToolContext(
                UUID.randomUUID(),
                ws,
                Instant.now().plusSeconds(60),
                () -> false,
                a -> {},
                null,
                hostCtx
        );

        // Attacker attempts to pass forged tenant in parameters
        String maliciousArgs = "{\"query\":\"SELECT 1\", \"tenantId\":\"hacker_tenant\"}";
        ToolOutcome outcome = tool.execute(maliciousArgs, toolContext);

        assertInstanceOf(ToolOutcome.Success.class, outcome);
        assertSame(hostCtx, service.lastInjectedContext.get());
        assertEquals("genuine_tenant_abc", service.lastInjectedContext.get().tenantId());
        assertEquals("carol", service.lastInjectedContext.get().userPrincipal());
    }

    @Test
    @DisplayName("DefaultAgentRunner end-to-end propagates TrustedContext to executed tools")
    void testRunnerEndToEndPropagation(@TempDir Path tempDir) throws IOException {
        Workspace ws = createWorkspace(tempDir, "runner_tc_test");
        AtomicReference<TrustedContext> captured = new AtomicReference<>();

        Tool auditTool = new Tool() {
            private final ToolDefinition def = new ToolDefinition("audit_tool", "Performs audit", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);

            @Override
            public ToolDefinition definition() {
                return def;
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                captured.set(context.trustedContext());
                return new ToolOutcome.Success("{\"status\":\"ok\"}");
            }
        };

        ArrayDeque<ModelResponse> responses = new ArrayDeque<>();
        responses.add(new ModelResponse("", List.of(new ToolCall("call_1", "audit_tool", "{}")), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        responses.add(new ModelResponse("Audit completed successfully.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        ModelGateway gateway = (instructions, goal, definitions) -> new ModelSession() {
            @Override
            public ModelResponse nextResponse(Duration timeout) {
                return responses.removeFirst();
            }

            @Override
            public void appendToolResult(String callId, ToolOutcome outcome) {}

            @Override
            public void appendUserMessage(String message) {}

            @Override
            public void appendRuntimeFeedback(String feedback) {}

            @Override
            public long estimatedInputTokens() {
                return 100;
            }

            @Override
            public void close() {}
        };

        RunLimits limits = new RunLimits(10, 5, Duration.ofSeconds(30), Duration.ofSeconds(60));
        AgentSpec spec = new AgentSpec("security-auditor", "Perform audits safely", Set.of("audit_tool"), limits,
                (snapshot, answer) -> new com.agent.agentcorelab.agent.CompletionCheck.Result(true, ""));

        DefaultAgentRunner runner = new DefaultAgentRunner(
                gateway,
                new ToolCatalog(List.of(auditTool)),
                event -> {}
        );

        TrustedContext hostCtx = TrustedContext.builder()
                .tenantId("enterprise_corp")
                .userPrincipal("dave_admin")
                .scopes("orders:admin", "users:read")
                .traceId("trace-xyz-123")
                .build();

        RunRequest request = new RunRequest("run audit", ws, hostCtx);
        RunResult result = runner.run(spec, request);

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertNotNull(captured.get());
        assertEquals("enterprise_corp", captured.get().tenantId());
        assertEquals("dave_admin", captured.get().userPrincipal());
        assertEquals("trace-xyz-123", captured.get().traceId());
        assertTrue(captured.get().hasScope("orders:admin"));
    }
}
