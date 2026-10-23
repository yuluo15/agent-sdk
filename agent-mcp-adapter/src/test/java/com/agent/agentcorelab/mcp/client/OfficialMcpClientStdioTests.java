package com.agent.agentcorelab.mcp.client;

import com.agent.agentcorelab.mcp.adapter.McpServerBridge;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OfficialMcpClient Stdio Subprocess Protocol and Process Lifecycle Tests")
class OfficialMcpClientStdioTests {

    private OfficialMcpClient client;

    @BeforeEach
    void setUp() {
        String javaCmd = ProcessHandle.current().info().command().orElse("java");
        String classPath = System.getProperty("java.class.path");

        client = OfficialMcpClient.builder("stdio-server")
                .stdio(javaCmd, List.of("-cp", classPath, "com.agent.agentcorelab.mcp.client.MockMcpStdioServerMain"), Map.of())
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Stdio handshake, process handle detection, tools listing, and tool execution")
    void testStdioProcessExecutionAndLifecycle() {
        assertFalse(client.isConnected());

        var initResult = client.initialize();
        assertTrue(client.isConnected());
        assertEquals("stdio-mock-server", initResult.serverName());
        assertEquals("3.0.0", initResult.serverVersion());

        // ProcessHandle and PID detection
        Optional<ProcessHandle> ph = client.processHandle();
        assertTrue(ph.isPresent(), "Subprocess handle should be present for Stdio transport");
        assertTrue(ph.get().isAlive(), "Subprocess should be alive after initialization");
        Optional<Long> pid = client.pid();
        assertTrue(pid.isPresent() && pid.get() > 0, "PID should be positive");

        // Tools listing
        List<McpTool> tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("calculate", tools.get(0).name());
        assertEquals("Math calculator", tools.get(0).description());

        // Call tool directly
        McpToolResult result = client.callTool("calculate", new ObjectMapper().createObjectNode().put("expr", "42*2"));
        assertFalse(result.isError());
        assertTrue(result.asText().contains("Calculation result: 84"));

        // Ping
        assertDoesNotThrow(() -> {
            Duration ping = client.ping();
            assertNotNull(ping);
        });

        // Test McpServerBridge integration with Stdio
        McpServerBridge bridge = McpServerBridge.builder(client)
                .toolPrefix("math_")
                .riskResolver(name -> RiskLevel.READ_ONLY)
                .build();

        assertEquals("stdio-server", bridge.serverName());
        assertTrue(bridge.pid().isPresent());
        List<Tool> bridged = bridge.getTools();
        assertEquals(1, bridged.size());

        Tool mathTool = bridged.get(0);
        assertEquals("math_calculate", mathTool.definition().name());
        ToolOutcome outcome = mathTool.execute("{\"expr\":\"42*2\"}", null);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        assertTrue(((ToolOutcome.Success) outcome).outputJson().contains("Calculation result: 84"));

        // Close and verify process termination
        client.close();
        assertFalse(client.isConnected());
        // Verify process has exited
        ph.get().onExit().join();
        assertFalse(ph.get().isAlive());
    }
}
