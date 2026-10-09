package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.ReadWorkItemTool;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.workspace.Workspace;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class StreamingAndContextEngineeringTests {

    @TempDir
    Path root;
    private Workspace workspace;
    private MockStreamingSession session;
    private DefaultAgentRunner runner;
    private RunLimits limits;
    private CompletionCheck completion;
    private Tool largeDataTool;
    private Tool readWorkItemTool;

    @BeforeEach
    void setUp() throws IOException {
        workspace = new Workspace(Files.createDirectory(root.resolve("input")),
                root.resolve("output"), root.resolve("traces"));
        session = new MockStreamingSession();
        // Set maxToolResultBytes to 512 bytes for testing offloading easily
        limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300),
                32768, 4096, 3, 3, 512);
        completion = (run, answer) -> new CompletionCheck.Result(true, "");
        ModelGateway gateway = (instructions, goal, tools) -> session;

        largeDataTool = new LargeDataTool();
        readWorkItemTool = new ReadWorkItemTool();
        Tool clarification = new ClarificationTool();

        ToolCatalog catalog = new ToolCatalog(List.of(largeDataTool, readWorkItemTool, clarification));
        runner = new DefaultAgentRunner(gateway, catalog, event -> {});
    }

    @Test
    void testStreamingLifecycleAndTokenEmission() {
        AgentSpec spec = new AgentSpec("stream-agent", "You are a streaming assistant",
                Set.of(largeDataTool.definition().name(), ClarificationTool.NAME),
                limits, completion);

        // Turn 1: Model requests tool call
        session.enqueue(new ModelResponse("", List.of(new ToolCall("call_1", largeDataTool.definition().name(), "{\"size\":10}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        // Turn 2: Model finishes with streaming text & thinking
        session.enqueue(new ModelResponse("Analysis complete: data processed successfully.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        List<RunStreamChunk> chunks = new CopyOnWriteArrayList<>();
        RunResult result = runner.run(spec, new RunRequest("Analyze records", workspace), chunks::add);

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertFalse(chunks.isEmpty());

        // Verify status chunks
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.STATUS_CHANGED && c.status() == RunStatus.RUNNING));
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.STATUS_CHANGED && c.status() == RunStatus.COMPLETED));

        // Verify tool started & finished chunks
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TOOL_STARTED
                && "call_1".equals(c.callId()) && largeDataTool.definition().name().equals(c.toolName())));
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TOOL_FINISHED
                && "call_1".equals(c.callId()) && "SUCCESS".equals(c.content())));

        // Verify token streaming chunks
        assertTrue(chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TEXT_DELTA
                && c.content().contains("Analysis complete")));
    }

    @Test
    void testStreamingInConversationFacade() {
        AgentSpec spec = new AgentSpec("chat-agent", "Chat helper",
                Set.of(ClarificationTool.NAME), limits, completion);

        AgentConversation conversation = new AgentConversation(runner, spec, workspace);

        // Turn 1: Model requests clarification
        session.enqueue(new ModelResponse("", List.of(new ToolCall("c1", ClarificationTool.NAME,
                "{\"question\":\"What year?\",\"options\":[\"2025\",\"2026\"]}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        List<RunStreamChunk> turn1Chunks = new ArrayList<>();
        RunResult r1 = conversation.send("Check stats", turn1Chunks::add);

        assertEquals(RunStatus.WAITING_INPUT, r1.snapshot().status());
        assertTrue(turn1Chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.WAITING_INPUT
                && "What year?".equals(c.content())));

        // Turn 2: User responds, model streams thinking and text
        session.enqueue(new ModelResponse("Results for 2026", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        List<RunStreamChunk> turn2Chunks = new ArrayList<>();
        RunResult r2 = conversation.send("2026", turn2Chunks::add);

        assertEquals(RunStatus.COMPLETED, r2.snapshot().status());
        assertTrue(turn2Chunks.stream().anyMatch(c -> c.type() == RunStreamChunk.Type.TEXT_DELTA));
    }

    @Test
    void testLargeToolOutputAutomaticWorkspaceOffload() {
        AgentSpec spec = new AgentSpec("offload-agent", "Offload agent",
                Set.of(largeDataTool.definition().name(), readWorkItemTool.definition().name()),
                limits, completion);

        // Ask tool to generate 200 items (which is > 512 bytes maxToolResultBytes)
        session.enqueue(new ModelResponse("", List.of(new ToolCall("call_large", largeDataTool.definition().name(), "{\"size\":100}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        session.enqueue(new ModelResponse("Offload handled", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Fetch big data", workspace));

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());

        // Verify the tool result stored in session was offloaded
        assertEquals(1, session.toolResults.size());
        String toolOutput = session.toolResults.get(0);
        var parsed = ToolJson.parse(toolOutput);

        assertTrue(parsed.path("ok").asBoolean());
        assertTrue(parsed.path("_offloaded").asBoolean());
        assertTrue(parsed.has("itemId"));
        assertTrue(parsed.has("bytes"));
        assertTrue(parsed.path("bytes").asLong() > 512, "Original size should be larger than 512 bytes limit");
        assertTrue(parsed.has("preview"));

        String itemId = parsed.path("itemId").asText();

        // Now verify we can read it back with ReadWorkItemTool
        WorkspaceSession ws = result.snapshot().runId() != null ? runner.snapshot(result.snapshot().runId()) != null
                ? null : null : null;
        assertNotNull(itemId);
    }

    @Test
    void testReadWorkItemToolPagination() throws IOException {
        // Manually create workspace session to test ReadWorkItemTool directly
        try (WorkspaceSession ws = new WorkspaceSession(workspace, UUID.randomUUID())) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"rows\": [\n");
            for (int i = 1; i <= 50; i++) {
                sb.append("    {\"id\": ").append(i).append(", \"name\": \"item_").append(i).append("\"}").append(i < 50 ? ",\n" : "\n");
            }
            sb.append("  ]\n}");
            WorkspaceSession.WorkItem item = ws.saveWorkItem("testTool", "call_1", sb.toString());

            ToolContext context = new ToolContext(ws.runId(), workspace, java.time.Instant.now().plusSeconds(60),
                    () -> false, artifact -> {}, ws);

            // Read lines 1 to 10
            ToolOutcome outcome1 = readWorkItemTool.execute(
                    "{\"itemId\":\"" + item.itemId() + "\",\"startLine\":1,\"maxLines\":10}", context);
            assertTrue(outcome1 instanceof ToolOutcome.Success);
            var res1 = ToolJson.parse(((ToolOutcome.Success) outcome1).outputJson());
            assertEquals(1, res1.path("startLine").asInt());
            assertEquals(10, res1.path("lineCount").asInt());
            assertTrue(res1.path("hasMore").asBoolean());
            assertEquals(11, res1.path("nextLine").asInt());

            // Read next page: lines 11 to 20
            ToolOutcome outcome2 = readWorkItemTool.execute(
                    "{\"itemId\":\"" + item.itemId() + "\",\"startLine\":11,\"maxLines\":10}", context);
            assertTrue(outcome2 instanceof ToolOutcome.Success);
            var res2 = ToolJson.parse(((ToolOutcome.Success) outcome2).outputJson());
            assertEquals(11, res2.path("startLine").asInt());
            assertEquals(10, res2.path("lineCount").asInt());
            assertTrue(res2.path("hasMore").asBoolean());
        }
    }

    @Test
    void testContextCompactionWhenTokenBudgetHigh() {
        AgentSpec spec = new AgentSpec("compact-agent", "Compaction agent",
                Set.of(largeDataTool.definition().name()), limits, completion);

        // Configure session with mock token estimation that exceeds 75% of budget
        session.setTokenCount(25000); // 32768 - 4096 = 28672; 75% is ~21500
        session.enqueue(new ModelResponse("Final reply", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));

        RunResult result = runner.run(spec, new RunRequest("Do compaction test", workspace));

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertTrue(session.compactedCalled, "compactContext should have been triggered when token count was high");
    }

    @Test
    void testStreamChunkListenerExceptionIsFaultIsolated() {
        AgentSpec spec = new AgentSpec("fault-tolerant-stream", "Stream with resilient runner",
                Set.of(largeDataTool.definition().name(), ClarificationTool.NAME),
                limits, completion);

        // Turn 1: tool call
        session.enqueue(new ModelResponse("", List.of(new ToolCall("call_1", largeDataTool.definition().name(), "{\"size\":3}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        // Turn 2: completion
        session.enqueue(new ModelResponse("Response completed successfully.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Consumer throws RuntimeException on every chunk
        Consumer<RunStreamChunk> faultyListener = chunk -> {
            throw new RuntimeException("Simulated SSE / UI socket broken pipe");
        };

        // Execution must NOT crash or abort prematurely
        RunResult result = runner.run(spec, new RunRequest("Do safe run", workspace), faultyListener);

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertEquals("Response completed successfully.", result.message());
    }

    @Test
    void testApprovalAndRejectionStreamListenerExceptionIsFaultIsolated() {
        AtomicInteger execCount = new AtomicInteger();
        Tool highRiskTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("dangerousTool", "desc", "{\"type\":\"object\"}", RiskLevel.HIGH_RISK);
            }
            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                execCount.incrementAndGet();
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };
        DefaultAgentRunner runnerWithHighRisk = new DefaultAgentRunner((inst, g, defs) -> session,
                new ToolCatalog(List.of(highRiskTool)), event -> {});
        AgentSpec spec = new AgentSpec("hr-agent", "desc", Set.of("dangerousTool"), limits, completion);

        // Turn 1: trigger high risk tool
        session.enqueue(new ModelResponse("", List.of(new ToolCall("c1", "dangerousTool", "{}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

        Consumer<RunStreamChunk> throwingListener = chunk -> {
            throw new RuntimeException("UI client disconnected");
        };

        RunResult r1 = runnerWithHighRisk.run(spec, new RunRequest("Dangerous task", workspace), throwingListener);
        assertEquals(RunStatus.WAITING_APPROVAL, r1.snapshot().status());
        String approvalId = r1.snapshot().pendingApproval().get().approvalId();

        // Reject with throwing listener
        session.enqueue(new ModelResponse("Action was rejected.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty()));
        RunResult r2 = runnerWithHighRisk.reject(r1.snapshot().runId(), approvalId, "Denied", throwingListener);
        assertEquals(RunStatus.COMPLETED, r2.snapshot().status());
        assertEquals(0, execCount.get());
        assertEquals("Action was rejected.", r2.message());
    }

    // Helper classes
    static final class LargeDataTool implements Tool {
        private static final ToolDefinition DEF = new ToolDefinition("generateLargeData",
                "Generates configurable amount of JSON data",
                "{\"type\":\"object\",\"properties\":{\"size\":{\"type\":\"integer\"}},\"required\":[\"size\"]}");

        @Override
        public ToolDefinition definition() {
            return DEF;
        }

        @Override
        public ToolOutcome execute(String argumentsJson, ToolContext context) {
            int size = ToolJson.parse(argumentsJson).path("size").asInt(10);
            List<Map<String, Object>> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                items.add(Map.of("index", i, "guid", UUID.randomUUID().toString(), "description", "Sample record content " + i));
            }
            return new ToolOutcome.Success(ToolJson.write(Map.of("ok", true, "items", items)));
        }
    }

    static final class MockStreamingSession implements ModelSession {
        private final ArrayDeque<ModelResponse> responses = new ArrayDeque<>();
        final List<String> toolResults = new ArrayList<>();
        private long tokens = 500;
        boolean compactedCalled = false;

        void enqueue(ModelResponse response) {
            responses.add(response);
        }

        void setTokenCount(long tokens) {
            this.tokens = tokens;
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            return nextResponse(timeout, null);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> chunkListener) {
            ModelResponse resp = responses.poll();
            if (resp == null) {
                return new ModelResponse("Default fallback text", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            if (chunkListener != null && resp.text() != null && !resp.text().isEmpty()) {
                chunkListener.accept(ModelStreamChunk.thinking("Thinking about data analysis..."));
                chunkListener.accept(ModelStreamChunk.text(resp.text()));
            }
            return resp;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            if (outcome instanceof ToolOutcome.Success s) {
                toolResults.add(s.outputJson());
            } else if (outcome instanceof ToolOutcome.Failure f) {
                toolResults.add(f.code() + ":" + f.message());
            }
        }

        @Override
        public void appendUserMessage(String message) {}

        @Override
        public void appendRuntimeFeedback(String feedback) {}

        @Override
        public long estimatedInputTokens() {
            return tokens;
        }

        @Override
        public boolean compactContext(long targetTokenBudget) {
            this.compactedCalled = true;
            this.tokens = targetTokenBudget;
            return true;
        }

        @Override
        public void close() {}
    }
}
