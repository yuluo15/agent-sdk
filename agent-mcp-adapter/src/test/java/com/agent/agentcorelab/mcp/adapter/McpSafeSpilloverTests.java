package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.mcp.client.DefaultMcpClient;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.transport.InMemoryMcpTransport;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolExecutor;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("McpSafeSpillover and WorkspaceSession Integration Tests")
class McpSafeSpilloverTests {

    @Test
    @DisplayName("Large MCP tool output automatically spills over into WorkspaceSession storage")
    void testLargeOutputSpillover(@TempDir Path tempDir) throws Exception {
        Path inputRoot = tempDir.resolve("input");
        Path outputRoot = tempDir.resolve("output");
        Path traceRoot = tempDir.resolve("traces");

        Workspace workspace = new Workspace(inputRoot, outputRoot, traceRoot, true);
        UUID runId = UUID.randomUUID();

        // 1. Setup mock MCP server returning large 20KB output
        InMemoryMcpTransport transport = new InMemoryMcpTransport();
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            largeContent.append("Row ").append(i).append(": Customer data record with detailed fields and values...\n");
        }
        String largeText = largeContent.toString();

        transport.setResponder(msg -> {
            if (msg instanceof JsonRpcRequest req) {
                if (McpProtocolConstants.METHOD_INITIALIZE.equals(req.method())) {
                    return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(
                            "{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"large-data-srv\"}}"
                    ));
                } else if (McpProtocolConstants.METHOD_TOOLS_CALL.equals(req.method())) {
                    String toolResult = "{\"content\":[{\"type\":\"text\",\"text\":\"" + largeText.replace("\n", "\\n") + "\"}],\"isError\":false}";
                    return JsonRpcResponse.success(req.id(), McpJsonRpcCodec.parseJson(toolResult));
                }
            }
            return null;
        });

        McpClient client = new DefaultMcpClient("spill-server", transport);
        client.initialize();

        McpTool mcpTool = new McpTool("fetch_all_records", "Fetches bulk records", McpJsonRpcCodec.parseJson("{\"type\":\"object\"}"));
        McpToolAdapter adapter = new McpToolAdapter(client, mcpTool, "db_", RiskLevel.READ_ONLY);

        // 2. Setup ToolCatalog and ToolExecutor with 4KB result threshold
        int maxResultBytes = 4096;
        ToolCatalog catalog = new ToolCatalog(List.of(adapter));
        ToolExecutor executor = new ToolExecutor(catalog, maxResultBytes);

        // 3. Execute within real WorkspaceSession
        try (WorkspaceSession session = new WorkspaceSession(workspace, runId)) {
            ToolContext context = new ToolContext(
                    runId,
                    workspace,
                    Instant.now().plusSeconds(60),
                    () -> false,
                    artifact -> {},
                    session
            );

            ToolCall call = new ToolCall("call_1", "db_fetch_all_records", "{}");
            ToolOutcome outcome = executor.execute(call, context);

            assertInstanceOf(ToolOutcome.Success.class, outcome);
            ToolOutcome.Success success = (ToolOutcome.Success) outcome;
            String resultJson = success.outputJson();

            // Verify safe spillover triggered
            assertTrue(resultJson.contains("\"_offloaded\":true"));
            assertTrue(resultJson.contains("itemId"));
            assertTrue(resultJson.contains("item_"));

            // Parse itemId and verify content was safely preserved in WorkspaceSession
            var node = McpJsonRpcCodec.parseJson(resultJson);
            String itemId = node.get("itemId").asText();
            assertNotNull(itemId);

            WorkspaceSession.WorkSlice slice = session.readWorkItemSlice(itemId, 1, 10);
            assertEquals(1, slice.startLine());
            assertEquals(1, slice.lineCount());
            assertTrue(slice.content().contains("Row 0: Customer data record"));
        } finally {
            client.close();
        }
    }
}
