package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.mcp.client.DefaultMcpClient;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.JsonRpcError;
import com.agent.agentcorelab.mcp.protocol.JsonRpcNotification;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.mcp.transport.InMemoryMcpTransport;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpClient, McpToolAdapter and McpServerBridge End-to-End Tests")
class McpClientAndAdapterTest {

    private InMemoryMcpTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() {
        transport = new InMemoryMcpTransport();

        // Configure mock server responses
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
                                      "name": "search_data",
                                      "description": "Searches internal database",
                                      "inputSchema": {
                                        "type": "object",
                                        "properties": { "query": { "type": "string" } },
                                        "required": ["query"]
                                      }
                                    },
                                    {
                                      "name": "delete_record",
                                      "description": "Deletes database record by id",
                                      "inputSchema": {
                                        "type": "object",
                                        "properties": { "id": { "type": "string" } },
                                        "required": ["id"]
                                      }
                                    }
                                  ]
                                }
                                """;
                        return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(toolsResult));
                    }
                    case McpProtocolConstants.METHOD_TOOLS_CALL -> {
                        String toolName = req.params().get("name").asText();
                        if ("search_data".equals(toolName)) {
                            String query = req.params().get("arguments").get("query").asText();
                            String toolResult = "{\"content\":[{\"type\":\"text\",\"text\":\"Found 5 items for: " + query + "\"}],\"isError\":false}";
                            return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(toolResult));
                        } else if ("delete_record".equals(toolName)) {
                            String errResult = "{\"content\":[{\"type\":\"text\",\"text\":\"Permission denied: cannot delete record\"}],\"isError\":true}";
                            return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(errResult));
                        }
                        return JsonRpcResponse.error(req.id(), JsonRpcError.internalError("Unknown tool: " + toolName));
                    }
                    case McpProtocolConstants.METHOD_PING -> {
                        return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.mapper().createObjectNode());
                    }
                    default -> {
                        return JsonRpcResponse.error(req.id(), JsonRpcError.methodNotFound(req.method()));
                    }
                }
            }
            return null;
        });

        client = new DefaultMcpClient("test-server", transport);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Handshake, listTools, and ping over client")
    void testClientHandshakeAndTools() {
        assertFalse(client.isConnected());

        var initResult = client.initialize();
        assertTrue(client.isConnected());
        assertEquals("mock-mcp-server", initResult.serverName());
        assertEquals("1.0.0", initResult.serverVersion());

        List<McpTool> tools = client.listTools();
        assertEquals(2, tools.size());
        assertEquals("search_data", tools.get(0).name());
        assertEquals("delete_record", tools.get(1).name());

        assertDoesNotThrow(() -> client.ping());
    }

    @Test
    @DisplayName("McpToolAdapter adapts tool execution and handles Success & Failure")
    void testMcpToolAdapterExecution() {
        client.initialize();
        List<McpTool> mcpTools = client.listTools();

        // 1. Success tool adapter
        McpTool searchTool = mcpTools.get(0);
        McpToolAdapter searchAdapter = new McpToolAdapter(client, searchTool, "mcp_", RiskLevel.READ_ONLY);

        assertEquals("mcp_search_data", searchAdapter.definition().name());
        assertEquals("Searches internal database", searchAdapter.definition().description());
        assertEquals(RiskLevel.READ_ONLY, searchAdapter.definition().riskLevel());

        ToolOutcome searchOutcome = searchAdapter.execute("{\"query\":\"customers\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, searchOutcome);
        ToolOutcome.Success success = (ToolOutcome.Success) searchOutcome;
        assertTrue(success.outputJson().contains("Found 5 items for: customers"));

        // 2. Failure tool adapter
        McpTool deleteTool = mcpTools.get(1);
        McpToolAdapter deleteAdapter = new McpToolAdapter(client, deleteTool, "mcp_", RiskLevel.HIGH_RISK);

        assertEquals("mcp_delete_record", deleteAdapter.definition().name());
        assertEquals(RiskLevel.HIGH_RISK, deleteAdapter.definition().riskLevel());

        ToolOutcome deleteOutcome = deleteAdapter.execute("{\"id\":\"rec-123\"}", null);
        assertInstanceOf(ToolOutcome.Failure.class, deleteOutcome);
        ToolOutcome.Failure failure = (ToolOutcome.Failure) deleteOutcome;
        assertEquals("MCP_TOOL_ERROR", failure.code());
        assertTrue(failure.message().contains("Permission denied"));
    }

    @Test
    @DisplayName("McpServerBridge discovers, prefixes, and filters tools")
    void testMcpServerBridge() {
        McpServerBridge bridge = McpServerBridge.builder(client)
                .toolPrefix("srv_")
                .exclude(Set.of("delete_record"))
                .build();

        List<Tool> tools = bridge.getTools();
        assertEquals(1, tools.size());
        Tool tool = tools.get(0);
        assertEquals("srv_search_data", tool.definition().name());

        assertNotNull(bridge.findTool("srv_search_data"));
        assertNull(bridge.findTool("srv_delete_record"));
    }

    @Test
    @DisplayName("McpServerManager aggregates tools from multiple bridges and shuts down cleanly")
    void testMcpServerManager() {
        McpServerBridge bridge = McpServerBridge.builder(client)
                .toolPrefix("mcp1_")
                .build();

        McpServerManager manager = new McpServerManager(List.of(bridge));
        assertEquals(2, manager.allTools().size());
        assertTrue(manager.findTool("mcp1_search_data").isPresent());

        manager.close();
        assertFalse(client.isConnected());
    }
}
