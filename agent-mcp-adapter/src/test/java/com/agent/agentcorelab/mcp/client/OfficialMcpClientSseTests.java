package com.agent.agentcorelab.mcp.client;

import com.agent.agentcorelab.mcp.adapter.McpServerBridge;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OfficialMcpClient Remote HTTP/SSE Protocol and Adapter Tests")
class OfficialMcpClientSseTests {

    private HttpServer server;
    private int port;
    private OfficialMcpClient client;

    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.BlockingQueue<String> sseQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean serverRunning = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedTenantHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        serverRunning.set(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        port = server.getAddress().getPort();

        server.createContext("/sse", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedTenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0); // chunked transfer
            OutputStream os = exchange.getResponseBody();

            String endpointEvent = "event: endpoint\ndata: /messages\n\n";
            os.write(endpointEvent.getBytes(StandardCharsets.UTF_8));
            os.flush();

            try {
                while (serverRunning.get()) {
                    String msg = sseQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        os.write(msg.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        });

        server.createContext("/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();

            try {
                JsonNode reqNode = mapper.readTree(body);
                JsonNode idNode = reqNode.get("id");
                if (idNode == null || idNode.isNull()) {
                    // notification, e.g. notifications/initialized
                    return;
                }

                String idVal = idNode.isNumber() ? idNode.toString() : "\"" + idNode.asText() + "\"";
                String method = reqNode.path("method").asText();

                String resultJson;
                if ("initialize".equals(method)) {
                    resultJson = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"remote-mock-mcp\",\"version\":\"2.1.0\"}}";
                } else if ("tools/list".equals(method)) {
                    resultJson = """
                            {
                              "tools": [
                                {
                                  "name": "remote_query",
                                  "description": "Queries remote database",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": { "sql": { "type": "string" } },
                                    "required": ["sql"]
                                  }
                                },
                                {
                                  "name": "remote_drop",
                                  "description": "Drop table from database",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": { "table": { "type": "string" } }
                                  }
                                }
                              ]
                            }
                            """;
                } else if ("tools/call".equals(method)) {
                    String toolName = reqNode.path("params").path("name").asText();
                    if ("remote_query".equals(toolName)) {
                        resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Query executed: 42 rows\"}],\"isError\":false}";
                    } else if ("remote_drop".equals(toolName)) {
                        resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Permission denied: dropping tables is prohibited\"}],\"isError\":true}";
                    } else {
                        resultJson = "{\"content\":[{\"type\":\"text\",\"text\":\"Unknown tool\"}],\"isError\":true}";
                    }
                } else {
                    resultJson = "{}";
                }

                String sseMsg = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + idVal + ",\"result\":" + resultJson.replaceAll("[\\r\\n]+", " ").trim() + "}\n\n";
                sseQueue.offer(sseMsg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        server.start();

        client = OfficialMcpClient.builder("remote-server")
                .sse("http://127.0.0.1:" + port, "/sse")
                .authorization("secret-bearer-token-123")
                .header("X-Tenant-Id", "tenant-alpha")
                .requestTimeout(Duration.ofSeconds(5))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() {
        serverRunning.set(false);
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Handshake, OAuth Bearer Authorization header, and tool discovery over remote HTTP/SSE")
    void testRemoteSseHandshakeAndTools() {
        assertFalse(client.isConnected());

        var initResult = client.initialize();
        assertTrue(client.isConnected());
        assertEquals("2024-11-05", initResult.protocolVersion());
        assertEquals("remote-mock-mcp", initResult.serverName());
        assertEquals("2.1.0", initResult.serverVersion());

        // Verify Bearer authorization and custom tenant headers were transferred to the remote endpoint
        assertEquals("Bearer secret-bearer-token-123", receivedAuthHeader.get());
        assertEquals("tenant-alpha", receivedTenantHeader.get());

        List<McpTool> tools = client.listTools();
        assertEquals(2, tools.size());
        assertEquals("remote_query", tools.get(0).name());
        assertEquals("Queries remote database", tools.get(0).description());
        assertEquals("remote_drop", tools.get(1).name());

        assertDoesNotThrow(() -> {
            Duration latency = client.ping();
            assertNotNull(latency);
            assertFalse(latency.isNegative());
        });
    }

    @Test
    @DisplayName("Tool execution and integration with McpServerBridge and McpToolAdapter over remote SSE")
    void testRemoteSseToolExecutionAndBridge() {
        McpServerBridge bridge = McpServerBridge.builder(client)
                .toolPrefix("rem_")
                .filter(name -> name.contains("query"))
                .riskResolver(name -> RiskLevel.READ_ONLY)
                .build();

        bridge.initialize();
        List<Tool> bridgedTools = bridge.getTools();

        // Whitelist filtered remote_drop out
        assertEquals(1, bridgedTools.size());
        Tool queryTool = bridgedTools.get(0);
        assertEquals("rem_remote_query", queryTool.definition().name());
        assertEquals(RiskLevel.READ_ONLY, queryTool.definition().riskLevel());

        // Execute remote query
        ToolOutcome outcome = queryTool.execute("{\"sql\":\"SELECT * FROM metrics\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        assertTrue(success.outputJson().contains("Query executed: 42 rows"));

        // Direct call to error tool
        McpToolResult dropResult = client.callTool("remote_drop", mapper.createObjectNode().put("table", "users"));
        assertTrue(dropResult.isError());
        assertTrue(dropResult.asText().contains("Permission denied"));

        bridge.close();
    }

    @Test
    @DisplayName("Asynchronous initializeAsync, listToolsAsync, and callToolAsync over SSE")
    void testRemoteSseAsyncOperations() {
        var initResult = client.initializeAsync().join();
        assertNotNull(initResult);
        assertEquals("remote-mock-mcp", initResult.serverName());

        List<McpTool> tools = client.listToolsAsync().join();
        assertEquals(2, tools.size());

        McpToolResult callResult = client.callToolAsync("remote_query", mapper.createObjectNode().put("sql", "SELECT 1")).join();
        assertFalse(callResult.isError());
        assertTrue(callResult.asText().contains("42 rows"));
    }
}
