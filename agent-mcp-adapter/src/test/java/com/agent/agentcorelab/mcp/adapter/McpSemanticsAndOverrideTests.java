package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.mcp.client.DefaultMcpClient;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.JsonRpcError;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.transport.InMemoryMcpTransport;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.SemanticHint;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolSemantics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Task 03 - MCP Governance Semantics and Host Override Tests")
class McpSemanticsAndOverrideTests {

    private InMemoryMcpTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() {
        transport = new InMemoryMcpTransport();
        transport.setResponder(msg -> {
            if (msg instanceof JsonRpcRequest req) {
                switch (req.method()) {
                    case McpProtocolConstants.METHOD_INITIALIZE -> {
                        String initResult = """
                                {
                                  "protocolVersion": "2024-11-05",
                                  "serverInfo": { "name": "mock-mcp-server", "version": "1.0.0" },
                                  "capabilities": { "tools": {} }
                                }
                                """;
                        return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(initResult));
                    }
                    case McpProtocolConstants.METHOD_TOOLS_LIST -> {
                        String toolsResult = """
                                {
                                  "tools": [
                                    {
                                      "name": "safe_query",
                                      "description": "Performs read only data inspection",
                                      "inputSchema": { "type": "object" }
                                    },
                                    {
                                      "name": "untrusted_eval",
                                      "description": "Claims to be safe calculator",
                                      "inputSchema": { "type": "object" }
                                    }
                                  ]
                                }
                                """;
                        return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(toolsResult));
                    }
                    default -> JsonRpcResponse.error(req.id(), new JsonRpcError(-32601, "Method not found: " + req.method(), null));
                }
            }
            return null;
        });

        client = new DefaultMcpClient("test-mcp-server", transport);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("McpToolAdapter respects explicit ToolSemantics")
    void testMcpToolAdapterWithSemantics() {
        McpTool mcpTool = new McpTool("sample_tool", "Description", null);

        ToolSemantics customSemantics = new ToolSemantics(
                SemanticHint.NO,
                SemanticHint.YES,
                SemanticHint.NO,
                SemanticHint.YES
        );

        McpToolAdapter adapter = new McpToolAdapter(
                client,
                mcpTool,
                "mcp_",
                RiskLevel.HIGH_RISK,
                customSemantics
        );

        assertEquals("mcp_sample_tool", adapter.definition().name());
        assertEquals(RiskLevel.HIGH_RISK, adapter.definition().riskLevel());
        assertFalse(adapter.definition().isReadOnly());
        assertTrue(adapter.definition().isDestructive());
        assertTrue(adapter.definition().semantics().isOpenWorld());
    }

    @Test
    @DisplayName("McpServerBridge host semanticsOverride takes authoritative precedence over untrusted tool claims")
    void testMcpServerBridgeHostSemanticsOverride() {
        // Host configures override:
        // untrusted_eval might claim to be harmless, but host explicitly marks it destructive=YES, readOnly=NO, openWorld=YES
        ToolSemantics strictOverride = ToolSemantics.builder()
                .readOnly(SemanticHint.NO)
                .destructive(SemanticHint.YES)
                .openWorld(SemanticHint.YES)
                .build();

        McpServerBridge bridge = McpServerBridge.builder(client)
                .toolPrefix("sec_")
                .semanticsOverride("untrusted_eval", strictOverride)
                .build();

        bridge.initialize();

        Tool safeQuery = bridge.findTool("sec_safe_query");
        assertNotNull(safeQuery);
        // By default, safe_query is READ_ONLY and readOnlySafe
        assertTrue(safeQuery.definition().isReadOnly());
        assertFalse(safeQuery.definition().isDestructive());

        Tool untrustedEval = bridge.findTool("sec_untrusted_eval");
        assertNotNull(untrustedEval);
        // Host override must take absolute precedence
        assertFalse(untrustedEval.definition().isReadOnly());
        assertTrue(untrustedEval.definition().isDestructive());
        assertEquals(SemanticHint.NO, untrustedEval.definition().semantics().readOnly());
        assertEquals(SemanticHint.YES, untrustedEval.definition().semantics().destructive());
        assertEquals(SemanticHint.YES, untrustedEval.definition().semantics().openWorld());
    }

    @Test
    @DisplayName("McpServerBridge semanticsResolver dynamic resolution")
    void testMcpServerBridgeDynamicSemanticsResolver() {
        McpServerBridge bridge = McpServerBridge.builder(client)
                .semanticsResolver(name -> {
                    if ("untrusted_eval".equals(name)) {
                        return ToolSemantics.destructiveAction();
                    }
                    return ToolSemantics.readOnlySafe();
                })
                .build();

        bridge.initialize();

        Tool safe = bridge.findTool("safe_query");
        assertTrue(safe.definition().isReadOnly());

        Tool untrusted = bridge.findTool("untrusted_eval");
        assertTrue(untrusted.definition().isDestructive());
        assertFalse(untrusted.definition().isReadOnly());
    }
}
