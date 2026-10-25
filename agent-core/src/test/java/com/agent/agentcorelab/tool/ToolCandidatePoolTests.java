package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.tool.annotation.AgentTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedToolScanner;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolCandidatePoolTests {

    private ToolCandidatePool pool;
    private RunLimits limits;

    @BeforeEach
    void setUp() {
        pool = ToolCandidatePool.create();
        limits = new RunLimits(20, 40, Duration.ofSeconds(10), Duration.ofSeconds(20),
                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15));
    }

    private Tool createSimpleTool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Mock tool " + name, "{\"type\":\"object\"}");
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"result\":\"ok\"}");
            }
        };
    }

    // =========================================================================
    // 1. Basic Registration & Discovery
    // =========================================================================

    @Test
    void testBuiltinToolsAreAutoRegistered() {
        assertTrue(pool.contains(ClarificationTool.NAME));
        assertTrue(pool.contains("readWorkItem"));
        assertTrue(pool.contains("updatePlan"));
        assertTrue(pool.contains("saveWorkNote"));
        assertTrue(pool.contains("checkDeliverable"));
        assertEquals(5, pool.size());
    }

    @Test
    void testRegisterAndFind() {
        Tool tool = createSimpleTool("orderQuery");
        CandidateTool candidate = CandidateTool.of(tool, "order", Set.of("query", "readonly"));

        pool.register(candidate);
        assertEquals(6, pool.size());
        assertTrue(pool.contains("orderQuery"));

        Optional<CandidateTool> found = pool.find("orderQuery");
        assertTrue(found.isPresent());
        assertEquals("order", found.get().domain());
        assertTrue(found.get().matchesTag("query"));
        assertTrue(found.get().matchesTag("readonly"));
        assertFalse(found.get().matchesTag("mutation"));

        // Unregister
        assertTrue(pool.unregister("orderQuery"));
        assertFalse(pool.contains("orderQuery"));
        assertEquals(5, pool.size());
    }

    @Test
    void testFilterByDomainAndTags() {
        pool.register(CandidateTool.of(createSimpleTool("queryOrder"), "order", Set.of("read", "sales")));
        pool.register(CandidateTool.of(createSimpleTool("cancelOrder"), "order", Set.of("write", "sales")));
        pool.register(CandidateTool.of(createSimpleTool("refundPayment"), "finance", Set.of("write", "money")));

        List<CandidateTool> orderTools = pool.filterByDomain("order");
        assertEquals(2, orderTools.size());

        List<CandidateTool> financeTools = pool.filterByDomain("finance");
        assertEquals(1, financeTools.size());
        assertEquals("refundPayment", financeTools.get(0).name());

        List<CandidateTool> writeTools = pool.filterByTags("write");
        assertEquals(2, writeTools.size());

        List<CandidateTool> moneyTools = pool.filterByTags("money");
        assertEquals(1, moneyTools.size());
    }

    // =========================================================================
    // 2. Least-Privilege Catalog Assembly
    // =========================================================================

    @Test
    void testAssembleCatalog_strictlyIsolatesTools() {
        pool.register(CandidateTool.of(createSimpleTool("tool_1"), "d1"));
        pool.register(CandidateTool.of(createSimpleTool("tool_2"), "d1"));
        pool.register(CandidateTool.of(createSimpleTool("tool_3"), "d2"));

        AgentSpec spec = new AgentSpec("agent-a", "instructions", Set.of("tool_1"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        ToolCatalog catalog = pool.assembleCatalog(spec);
        assertEquals(1, catalog.definitions().size());
        assertEquals("tool_1", catalog.definitions().get(0).name());
        assertNotNull(catalog.require("tool_1"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("tool_2"));
        assertThrows(IllegalArgumentException.class, () -> catalog.require("tool_3"));
    }

    @Test
    void testAssembleCatalog_missingToolThrowsToolAssemblyException() {
        AgentSpec spec = new AgentSpec("agent-x", "instructions", Set.of("non_existent_tool"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class, () -> pool.assembleCatalog(spec));
        assertEquals("agent-x", ex.agentId());
        assertEquals("non_existent_tool", ex.toolName());
        assertEquals("TOOL_NOT_FOUND", ex.violationCode());
        assertTrue(ex.getMessage().contains("was not found in the Candidate Tool Pool"));
    }

    @Test
    void testAssembleCatalog_disabledToolThrowsToolAssemblyException() {
        Tool tool = createSimpleTool("disabled_tool");
        pool.register(CandidateTool.of(tool, "domain").withEnabled(false));

        AgentSpec spec = new AgentSpec("agent-y", "instructions", Set.of("disabled_tool"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class, () -> pool.assembleCatalog(spec));
        assertEquals("agent-y", ex.agentId());
        assertEquals("disabled_tool", ex.toolName());
        assertEquals("TOOL_DISABLED", ex.violationCode());
    }

    // =========================================================================
    // 3. Tenant Isolation
    // =========================================================================

    @Test
    void testTenantIsolation_allowedTenantSucceeds() {
        Tool tool = createSimpleTool("tenant_specific_tool");
        CandidateTool candidate = CandidateTool.of(tool, "corp", Set.of(), "", Set.of("tenant-alpha", "tenant-beta"));
        pool.register(candidate);

        AgentSpec spec = new AgentSpec("tenant-agent", "instructions", Set.of("tenant_specific_tool"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        TrustedContext contextAlpha = new TrustedContext("tenant-alpha", "alice", "agent-1", Set.of("USER"));
        ToolCatalog catalogAlpha = pool.assembleCatalog(spec, contextAlpha);
        assertEquals(1, catalogAlpha.definitions().size());

        // Unauthorized tenant
        TrustedContext contextGamma = new TrustedContext("tenant-gamma", "charlie", "agent-1", Set.of("USER"));
        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class,
                () -> pool.assembleCatalog(spec, contextGamma));
        assertEquals("TENANT_ISOLATION_VIOLATION", ex.violationCode());
        assertEquals("tenant_specific_tool", ex.toolName());
        assertTrue(ex.getMessage().contains("tenant-gamma"));
    }

    // =========================================================================
    // 4. Role / Scope Authorization Enforcement
    // =========================================================================

    @Test
    void testRoleAuthorization_insufficientRoleThrowsException() {
        Tool sensitiveTool = createSimpleTool("deleteDatabase");
        CandidateTool candidate = CandidateTool.of(sensitiveTool, "ops", Set.of("admin"), "SYS_ADMIN");
        pool.register(candidate);

        AgentSpec spec = new AgentSpec("ops-agent", "instructions", Set.of("deleteDatabase"), limits,
                (s, a) -> CompletionCheck.Result.satisfied());

        // Caller with normal USER scope fails
        TrustedContext regularUser = new TrustedContext("default", "bob", "agent-1", Set.of("USER", "READONLY"));
        ToolAssemblyException ex = assertThrows(ToolAssemblyException.class,
                () -> pool.assembleCatalog(spec, regularUser));
        assertEquals("ROLE_UNAUTHORIZED", ex.violationCode());
        assertEquals("deleteDatabase", ex.toolName());
        assertTrue(ex.getMessage().contains("SYS_ADMIN"));

        // Caller with SYS_ADMIN scope succeeds
        TrustedContext adminUser = new TrustedContext("default", "root", "agent-1", Set.of("SYS_ADMIN"));
        ToolCatalog adminCatalog = pool.assembleCatalog(spec, adminUser);
        assertEquals(1, adminCatalog.definitions().size());
        assertEquals("deleteDatabase", adminCatalog.definitions().get(0).name());

        // Caller with wildcard "*" scope succeeds
        TrustedContext superUser = new TrustedContext("default", "super", "agent-1", Set.of("*"));
        ToolCatalog superCatalog = pool.assembleCatalog(spec, superUser);
        assertEquals(1, superCatalog.definitions().size());
    }

    // =========================================================================
    // 5. Annotated Tool Scanner Metadata Inference
    // =========================================================================

    public static class SampleToolBean {
        @AgentTool(
                name = "billing_refund",
                description = "Process customer refund",
                riskLevel = RiskLevel.HIGH_RISK,
                domain = "finance",
                tags = {"money", "billing", "p0"},
                requiredRole = "FINANCE_MANAGER",
                allowedTenants = {"corp-hq"}
        )
        public String refund(String orderId) {
            return "refunded:" + orderId;
        }
    }

    @Test
    void testAnnotatedToolScanner_populatesCandidateToolMetadata() {
        SampleToolBean bean = new SampleToolBean();
        List<CandidateTool> candidates = AnnotatedToolScanner.scanCandidates(bean);

        assertEquals(1, candidates.size());
        CandidateTool candidate = candidates.get(0);
        assertEquals("billing_refund", candidate.name());
        assertEquals("finance", candidate.domain());
        assertTrue(candidate.matchesTag("money"));
        assertTrue(candidate.matchesTag("billing"));
        assertTrue(candidate.matchesTag("p0"));
        assertEquals("FINANCE_MANAGER", candidate.requiredRole());
        assertTrue(candidate.isAllowedForTenant("corp-hq"));
        assertFalse(candidate.isAllowedForTenant("other-tenant"));
        assertTrue(candidate.definition().isHighRisk());
    }
}
