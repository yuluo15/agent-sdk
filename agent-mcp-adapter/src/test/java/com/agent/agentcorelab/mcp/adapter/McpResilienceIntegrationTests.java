package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.McpInitializeResult;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MCP Tool Exception Resilience & DefaultAgentRunner Integration Tests")
class McpResilienceIntegrationTests {

    private abstract static class AbstractTestMcpClient implements McpClient {
        @Override public String serverName() { return "test-mcp-server"; }
        @Override public boolean isConnected() { return true; }
        @Override public McpInitializeResult initialize() { return null; }
        @Override public CompletableFuture<McpInitializeResult> initializeAsync() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public List<McpTool> listTools() { return List.of(); }
        @Override public CompletableFuture<List<McpTool>> listToolsAsync() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<McpToolResult> callToolAsync(String toolName, JsonNode arguments) {
            return CompletableFuture.completedFuture(callTool(toolName, arguments));
        }
        @Override public Duration ping() { return Duration.ZERO; }
        @Override public void close() {}
    }

    @Test
    @DisplayName("Agent continues and successfully completes when an MCP tool throws an execution exception")
    void testAgentRecoversWhenMcpToolFailsWithException(@TempDir Path tempDir) {
        AtomicInteger callCount = new AtomicInteger();

        // Simulated MCP client that throws an exception on tool call
        McpClient faultyClient = new AbstractTestMcpClient() {
            @Override
            public McpToolResult callTool(String name, JsonNode arguments) {
                callCount.incrementAndGet();
                throw new RuntimeException("Connection refused: remote MCP daemon unreachable");
            }
        };

        McpTool mcpTool = new McpTool("fetch_weather", "Fetch weather data", McpJsonRpcCodec.parseJson("{\"type\":\"object\"}"));
        McpToolAdapter adapter = new McpToolAdapter(faultyClient, mcpTool, "mcp_", RiskLevel.READ_ONLY);

        // Simulated ModelSession that attempts to call the MCP tool, receives error observation, and then adapts
        MockScriptModelSession session = new MockScriptModelSession();

        // Step 1: Model tries to call the MCP tool
        session.addResponse(new ModelResponse(
                "Let me query the weather tool.",
                List.of(new ToolCall("call_1", "mcp_fetch_weather", "{\"city\":\"Hangzhou\"}")),
                ModelResponse.FinishReason.TOOL_CALLS,
                Optional.empty()
        ));

        // Step 2: Model receives tool error observation, gracefully provides a fallback answer without failing
        session.addResponse(new ModelResponse(
                "The external weather service is temporarily unavailable. Using cached forecast: Sunny, 22°C.",
                List.of(),
                ModelResponse.FinishReason.STOP,
                Optional.empty()
        ));

        ModelGateway gateway = (instructions, goal, definitions) -> session;
        ToolCatalog catalog = new ToolCatalog(List.of(adapter));
        List<RunEvent> events = new ArrayList<>();
        DefaultAgentRunner runner = new DefaultAgentRunner(gateway, catalog, events::add);

        Workspace workspace = new Workspace(tempDir.resolve("input"), tempDir.resolve("output"), tempDir.resolve("traces"), true);
        RunLimits limits = new RunLimits(10, 3, Duration.ofSeconds(30), Duration.ofSeconds(60));
        CompletionCheck completion = (run, answer) -> new CompletionCheck.Result(true, "");
        AgentSpec spec = new AgentSpec("weather-agent", "Resilient weather assistant", Set.of("mcp_fetch_weather"), limits, completion);

        RunResult result = runner.run(spec, new RunRequest("Check weather in Hangzhou", workspace));

        // Verifications:
        // 1. Run must be COMPLETED instead of being killed by terminal failure
        assertEquals(RunStatus.COMPLETED, result.snapshot().status(),
                "Agent run should complete successfully after recovering from non-terminal MCP tool failure");
        assertEquals("The external weather service is temporarily unavailable. Using cached forecast: Sunny, 22°C.", result.message());

        // 2. Metrics check: 1 tool call made, 2 model requests
        assertEquals(1, callCount.get());
        assertEquals(2, result.snapshot().metrics().modelRequests());
        assertEquals(1, result.snapshot().metrics().toolCalls());

        // 3. Tool results inspection
        assertEquals(1, session.appendedOutcomes.size());
        ToolOutcome outcome = session.appendedOutcomes.get(0);
        assertInstanceOf(ToolOutcome.Failure.class, outcome);
        ToolOutcome.Failure failure = (ToolOutcome.Failure) outcome;
        assertEquals("MCP_EXECUTION_FAILED", failure.code());
        assertFalse(failure.terminal(), "Terminal flag must be false");
        assertTrue(failure.message().contains("Connection refused: remote MCP daemon unreachable"));
    }

    @Test
    @DisplayName("Agent gracefully halts with STOPPED status when consecutive MCP tool errors exceed policy limit")
    void testAgentStopsGracefullyWhenConsecutiveErrorsExceedLimit(@TempDir Path tempDir) {
        AtomicInteger callCount = new AtomicInteger();

        McpClient alwaysFailingClient = new AbstractTestMcpClient() {
            @Override
            public McpToolResult callTool(String name, JsonNode arguments) {
                callCount.incrementAndGet();
                throw new RuntimeException("Internal MCP engine crash");
            }
        };

        McpTool mcpTool = new McpTool("unstable_tool", "Unstable tool", McpJsonRpcCodec.parseJson("{\"type\":\"object\"}"));
        McpToolAdapter adapter = new McpToolAdapter(alwaysFailingClient, mcpTool, "mcp_", RiskLevel.READ_ONLY);

        MockScriptModelSession session = new MockScriptModelSession();

        // Model repeatedly tries to call the failing tool without backoff
        for (int i = 1; i <= 5; i++) {
            session.addResponse(new ModelResponse(
                    "Attempting tool call " + i,
                    List.of(new ToolCall("call_" + i, "mcp_unstable_tool", "{}")),
                    ModelResponse.FinishReason.TOOL_CALLS,
                    Optional.empty()
            ));
        }

        ModelGateway gateway = (instructions, goal, definitions) -> session;
        ToolCatalog catalog = new ToolCatalog(List.of(adapter));
        DefaultAgentRunner runner = new DefaultAgentRunner(gateway, catalog, event -> {});

        Workspace workspace = new Workspace(tempDir.resolve("input"), tempDir.resolve("output"), tempDir.resolve("traces"), true);
        // Explicitly set maxConsecutiveToolErrors to 2, with maxToolCalls set to 20
        RunLimits limits = new RunLimits(
                10, // maxModelRequests
                20, // maxToolCalls
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                32768,
                4096,
                3,
                2,  // maxConsecutiveToolErrors
                16384
        );
        CompletionCheck completion = (run, answer) -> new CompletionCheck.Result(true, "");
        AgentSpec spec = new AgentSpec("loop-agent", "Looping agent", Set.of("mcp_unstable_tool"), limits, completion);

        RunResult result = runner.run(spec, new RunRequest("Run repeated unstable tool", workspace));

        // Verifications:
        // Must be gracefully STOPPED by policy limit, NOT unhandled FAILED
        assertEquals(RunStatus.STOPPED, result.snapshot().status());
        assertEquals("TOOL_ERROR_LIMIT", result.stopReason());
        assertEquals("Too many consecutive correctable tool errors", result.message());
        assertEquals(2, callCount.get(), "Should halt exactly when consecutive errors reach the limit (2)");
    }

    private static final class MockScriptModelSession implements ModelSession {
        private final ArrayDeque<Supplier<ModelResponse>> responses = new ArrayDeque<>();
        final List<ToolOutcome> appendedOutcomes = new ArrayList<>();
        private boolean closed;

        void addResponse(ModelResponse response) {
            responses.add(() -> response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            if (responses.isEmpty()) {
                return new ModelResponse("No more planned steps", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            return responses.removeFirst().get();
        }

        @Override
        public long estimatedInputTokens() {
            return 50;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            appendedOutcomes.add(outcome);
        }

        @Override
        public void appendUserMessage(String message) {}

        @Override
        public void appendRuntimeFeedback(String value) {}

        @Override
        public void close() {
            closed = true;
        }
    }
}
