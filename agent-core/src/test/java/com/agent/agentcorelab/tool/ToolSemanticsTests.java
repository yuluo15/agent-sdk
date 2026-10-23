package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.tool.annotation.AgentTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 03 - Tool Governance Semantics & Host Override Tests")
class ToolSemanticsTests {

    @Test
    @DisplayName("SemanticHint enum behavior, parsing and conversions")
    void testSemanticHint() {
        assertTrue(SemanticHint.YES.isYes());
        assertFalse(SemanticHint.YES.isNo());
        assertFalse(SemanticHint.YES.isUnknown());

        assertTrue(SemanticHint.NO.isNo());
        assertFalse(SemanticHint.NO.isYes());
        assertFalse(SemanticHint.NO.isUnknown());

        assertTrue(SemanticHint.UNKNOWN.isUnknown());
        assertFalse(SemanticHint.UNKNOWN.isYes());
        assertFalse(SemanticHint.UNKNOWN.isNo());

        // fromBoolean
        assertEquals(SemanticHint.YES, SemanticHint.fromBoolean(true));
        assertEquals(SemanticHint.NO, SemanticHint.fromBoolean(false));
        assertEquals(SemanticHint.UNKNOWN, SemanticHint.fromBoolean(null));

        // parse
        assertEquals(SemanticHint.YES, SemanticHint.parse("YES"));
        assertEquals(SemanticHint.YES, SemanticHint.parse("true"));
        assertEquals(SemanticHint.YES, SemanticHint.parse("1"));
        assertEquals(SemanticHint.YES, SemanticHint.parse("y"));
        assertEquals(SemanticHint.NO, SemanticHint.parse("NO"));
        assertEquals(SemanticHint.NO, SemanticHint.parse("false"));
        assertEquals(SemanticHint.NO, SemanticHint.parse("0"));
        assertEquals(SemanticHint.NO, SemanticHint.parse("n"));
        assertEquals(SemanticHint.UNKNOWN, SemanticHint.parse(null));
        assertEquals(SemanticHint.UNKNOWN, SemanticHint.parse(""));
        assertEquals(SemanticHint.UNKNOWN, SemanticHint.parse("invalid_str"));
    }

    @Test
    @DisplayName("ToolSemantics creation, null safety and factory defaults")
    void testToolSemanticsDefaultsAndSafety() {
        // null safety defaults to UNKNOWN
        ToolSemantics empty = new ToolSemantics(null, null, null, null);
        assertEquals(SemanticHint.UNKNOWN, empty.readOnly());
        assertEquals(SemanticHint.UNKNOWN, empty.destructive());
        assertEquals(SemanticHint.UNKNOWN, empty.idempotent());
        assertEquals(SemanticHint.UNKNOWN, empty.openWorld());

        // readOnlySafe
        ToolSemantics safe = ToolSemantics.readOnlySafe();
        assertTrue(safe.isGuaranteedReadOnly());
        assertFalse(safe.isPotentiallyDestructive());
        assertTrue(safe.isIdempotent());
        assertFalse(safe.isOpenWorld());

        // destructiveAction
        ToolSemantics destructive = ToolSemantics.destructiveAction();
        assertFalse(destructive.isGuaranteedReadOnly());
        assertTrue(destructive.isPotentiallyDestructive());
        assertFalse(destructive.isIdempotent());

        // defaultFor RiskLevel
        ToolSemantics forReadOnly = ToolSemantics.defaultFor(RiskLevel.READ_ONLY);
        assertEquals(SemanticHint.YES, forReadOnly.readOnly());
        assertEquals(SemanticHint.NO, forReadOnly.destructive());

        ToolSemantics forHighRisk = ToolSemantics.defaultFor(RiskLevel.HIGH_RISK);
        assertEquals(SemanticHint.NO, forHighRisk.readOnly());
        assertEquals(SemanticHint.YES, forHighRisk.destructive());
    }

    @Test
    @DisplayName("ToolSemantics host override merging")
    void testToolSemanticsHostOverride() {
        // Base: external tool claims readOnly=YES, but openWorld=UNKNOWN, destructive=UNKNOWN
        ToolSemantics externalClaim = new ToolSemantics(
                SemanticHint.YES,
                SemanticHint.UNKNOWN,
                SemanticHint.UNKNOWN,
                SemanticHint.UNKNOWN
        );

        // Host override: host knows this tool actually performs destructive mutations and calls external web
        ToolSemantics hostOverride = ToolSemantics.builder()
                .readOnly(SemanticHint.NO)
                .destructive(SemanticHint.YES)
                .openWorld(SemanticHint.YES)
                .build();

        ToolSemantics merged = externalClaim.overrideWith(hostOverride);

        assertEquals(SemanticHint.NO, merged.readOnly());
        assertEquals(SemanticHint.YES, merged.destructive());
        assertEquals(SemanticHint.YES, merged.openWorld());
        // Idempotent was not overridden in host, so remains UNKNOWN
        assertEquals(SemanticHint.UNKNOWN, merged.idempotent());

        // Null override returns same
        assertSame(externalClaim, externalClaim.overrideWith(null));
    }

    @Test
    @DisplayName("ToolDefinition backward compatibility and semantics integration")
    void testToolDefinitionBackwardCompatibility() {
        // 3-param constructor defaults to READ_ONLY and readOnlySafe
        ToolDefinition def3 = new ToolDefinition("queryLogs", "Queries log entries", "{\"type\":\"object\"}");
        assertEquals(RiskLevel.READ_ONLY, def3.riskLevel());
        assertTrue(def3.isReadOnly());
        assertFalse(def3.isDestructive());
        assertFalse(def3.isHighRisk());

        // 4-param constructor
        ToolDefinition def4 = new ToolDefinition("deleteRecord", "Deletes database record", "{\"type\":\"object\"}", RiskLevel.HIGH_RISK);
        assertEquals(RiskLevel.HIGH_RISK, def4.riskLevel());
        assertTrue(def4.isHighRisk());
        assertTrue(def4.isDestructive());
        assertFalse(def4.isReadOnly());

        // 5-param constructor with customized semantics
        ToolSemantics customSemantics = new ToolSemantics(
                SemanticHint.YES,
                SemanticHint.NO,
                SemanticHint.YES,
                SemanticHint.YES // read-only but open world (e.g. web search)
        );
        ToolDefinition def5 = new ToolDefinition("webSearch", "Searches public web", "{\"type\":\"object\"}", RiskLevel.READ_ONLY, customSemantics);
        assertTrue(def5.isReadOnly());
        assertTrue(def5.semantics().isOpenWorld());

        // Override semantics on ToolDefinition
        ToolDefinition overridden = def5.withOverriddenSemantics(
                new ToolSemantics(SemanticHint.UNKNOWN, SemanticHint.UNKNOWN, SemanticHint.UNKNOWN, SemanticHint.NO)
        );
        assertEquals(SemanticHint.YES, overridden.semantics().readOnly());
        assertEquals(SemanticHint.NO, overridden.semantics().openWorld()); // overridden to closed-world
    }

    @Test
    @DisplayName("ToolCatalog host semantics overrides via withSemanticsOverrides")
    void testToolCatalogSemanticsOverrides() {
        Tool originalTool = new Tool() {
            private final ToolDefinition def = new ToolDefinition(
                    "untrustedMcpTool",
                    "External MCP tool",
                    "{\"type\":\"object\"}",
                    RiskLevel.READ_ONLY
            );

            @Override
            public ToolDefinition definition() {
                return def;
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"executed\":true}");
            }
        };

        ToolCatalog catalog = new ToolCatalog(List.of(originalTool));
        Tool toolBefore = catalog.require("untrustedMcpTool");
        assertTrue(toolBefore.definition().isReadOnly());
        assertFalse(toolBefore.definition().isDestructive());

        // Host overrides: mark this tool as destructive=YES and readOnly=NO
        ToolSemantics hostOverride = ToolSemantics.builder()
                .readOnly(SemanticHint.NO)
                .destructive(SemanticHint.YES)
                .build();

        ToolCatalog securedCatalog = catalog.withSemanticsOverrides(Map.of("untrustedMcpTool", hostOverride));
        Tool toolAfter = securedCatalog.require("untrustedMcpTool");

        assertInstanceOf(OverriddenTool.class, toolAfter);
        assertFalse(toolAfter.definition().isReadOnly());
        assertTrue(toolAfter.definition().isDestructive());
        assertEquals(SemanticHint.NO, toolAfter.definition().semantics().readOnly());
        assertEquals(SemanticHint.YES, toolAfter.definition().semantics().destructive());

        // Execution is properly delegated
        ToolOutcome outcome = toolAfter.execute("{}", null);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        assertTrue(((ToolOutcome.Success) outcome).outputJson().contains("\"executed\":true"));
    }

    static class SampleToolService {
        @AgentTool(
                name = "annotatedQuery",
                description = "Standard query",
                riskLevel = RiskLevel.READ_ONLY
        )
        public String query(String sql) {
            return "rows";
        }

        @AgentTool(
                name = "annotatedDestructive",
                description = "Destructive refund tool",
                riskLevel = RiskLevel.HIGH_RISK,
                readOnly = SemanticHint.NO,
                destructive = SemanticHint.YES,
                idempotent = SemanticHint.NO,
                openWorld = SemanticHint.NO
        )
        public String refund(double amount) {
            return "refunded";
        }
    }

    @Test
    @DisplayName("@AgentTool annotation resolves governance semantics accurately")
    void testAnnotatedToolGovernanceSemantics() throws Exception {
        SampleToolService service = new SampleToolService();

        // 1. Implicit defaults from riskLevel
        var queryMethod = SampleToolService.class.getMethod("query", String.class);
        AnnotatedTool queryTool = new AnnotatedTool(service, queryMethod);
        assertTrue(queryTool.definition().isReadOnly());
        assertFalse(queryTool.definition().isDestructive());
        assertEquals(SemanticHint.YES, queryTool.definition().semantics().readOnly());
        assertEquals(SemanticHint.NO, queryTool.definition().semantics().destructive());

        // 2. Explicit semantic declarations on annotation
        var refundMethod = SampleToolService.class.getMethod("refund", double.class);
        AnnotatedTool refundTool = new AnnotatedTool(service, refundMethod);
        assertFalse(refundTool.definition().isReadOnly());
        assertTrue(refundTool.definition().isDestructive());
        assertEquals(SemanticHint.NO, refundTool.definition().semantics().readOnly());
        assertEquals(SemanticHint.YES, refundTool.definition().semantics().destructive());
        assertEquals(SemanticHint.NO, refundTool.definition().semantics().idempotent());
        assertEquals(SemanticHint.NO, refundTool.definition().semantics().openWorld());
    }
}
