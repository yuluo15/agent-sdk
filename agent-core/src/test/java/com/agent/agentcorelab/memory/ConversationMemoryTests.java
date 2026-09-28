package com.agent.agentcorelab.memory;

import com.agent.agentcorelab.agent.AgentConversation;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.ExecutionPolicy;
import com.agent.agentcorelab.agent.RunLimits;
import com.agent.agentcorelab.agent.RunRequest;
import com.agent.agentcorelab.agent.RunResult;
import com.agent.agentcorelab.agent.RunStatus;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class ConversationMemoryTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private AgentSpec spec;
    private final RunEventSink eventSink = event -> {};

    @BeforeEach
    void setUp() throws IOException {
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        Path traceDir = tempDir.resolve("traces");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(traceDir);
        workspace = new Workspace(inputDir, outputDir, traceDir);

        spec = new AgentSpec("concierge", "You are a hotel concierge.", Set.of(),
                new RunLimits(5, 5, Duration.ofSeconds(10), Duration.ofSeconds(30)),
                (run, answer) -> com.agent.agentcorelab.agent.CompletionCheck.Result.satisfied(),
                ExecutionPolicy.conversational());
    }

    @Test
    void testBasicMemoryAppendAndRetrieve() {
        ConversationMemory memory = new InMemoryConversationMemory(100, 10);
        String convId = "test-session-1";

        assertEquals(0, memory.getHistory(convId).size());

        memory.append(convId, ChatMessage.user("你好"));
        memory.append(convId, ChatMessage.assistant("您好！请问有什么可以帮您？"));

        List<ChatMessage> history = memory.getHistory(convId);
        assertEquals(2, history.size());
        assertEquals(ChatMessage.Role.USER, history.get(0).role());
        assertEquals("你好", history.get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, history.get(1).role());
        assertEquals("您好！请问有什么可以帮您？", history.get(1).content());

        // Immutability test
        assertThrows(UnsupportedOperationException.class, () -> history.add(ChatMessage.user("hack")));
    }

    @Test
    void testSlidingWindowMessageTrimming() {
        ConversationMemory memory = new InMemoryConversationMemory(100, 4);
        String convId = "test-window";

        for (int i = 1; i <= 6; i++) {
            memory.append(convId, ChatMessage.user("msg-" + i));
        }

        List<ChatMessage> history = memory.getHistory(convId);
        assertEquals(4, history.size());
        assertEquals("msg-3", history.get(0).content());
        assertEquals("msg-4", history.get(1).content());
        assertEquals("msg-5", history.get(2).content());
        assertEquals("msg-6", history.get(3).content());
    }

    @Test
    void testLruConversationEviction() {
        ConversationMemory memory = new InMemoryConversationMemory(2, 10);

        memory.append("conv-1", ChatMessage.user("Hello 1"));
        memory.append("conv-2", ChatMessage.user("Hello 2"));
        assertEquals(2, memory.activeConversationCount());

        // Access conv-1 so conv-2 becomes the least recently accessed
        memory.getHistory("conv-1");

        // Add conv-3, which should evict conv-2
        memory.append("conv-3", ChatMessage.user("Hello 3"));
        assertEquals(2, memory.activeConversationCount());

        assertTrue(memory.getHistory("conv-2").isEmpty());
        assertFalse(memory.getHistory("conv-1").isEmpty());
        assertFalse(memory.getHistory("conv-3").isEmpty());
    }

    @Test
    void testMultiTurnConversationMemoryEndToEnd() {
        // Track what history the model received on each session open
        List<List<ChatMessage>> capturedHistories = new ArrayList<>();
        List<String> capturedGoals = new ArrayList<>();

        ModelGateway mockGateway = new ModelGateway() {
            @Override
            public ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools) {
                return openSession(instructions, List.of(), goal, tools);
            }

            @Override
            public ModelSession openSession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
                capturedHistories.add(history != null ? List.copyOf(history) : List.of());
                capturedGoals.add(goal);

                return new ModelSession() {
                    @Override
                    public ModelResponse nextResponse(Duration timeout) {
                        if (goal.contains("10月1日")) {
                            return new ModelResponse(
                                    "在为您查询房态前，还需要确认一项信息：请问入住客人的姓名是？",
                                    List.of(),
                                    ModelResponse.FinishReason.STOP,
                                    java.util.Optional.empty()
                            );
                        } else if (goal.equals("张鹏")) {
                            return new ModelResponse(
                                    "已成功为您预订10月1日标准间，入住客人张鹏！",
                                    List.of(),
                                    ModelResponse.FinishReason.STOP,
                                    java.util.Optional.empty()
                            );
                        }
                        return new ModelResponse("收到", List.of(), ModelResponse.FinishReason.STOP, java.util.Optional.empty());
                    }

                    @Override public void appendToolResult(String callId, ToolOutcome outcome) {}
                    @Override public void appendUserMessage(String message) {}
                    @Override public void appendRuntimeFeedback(String feedback) {}
                    @Override public long estimatedInputTokens() { return 100; }
                    @Override public void close() {}
                };
            }
        };

        ConversationMemory memory = new InMemoryConversationMemory();
        DefaultAgentRunner runner = new DefaultAgentRunner(mockGateway, new ToolCatalog(List.of()),
                eventSink, memory);

        String conversationId = "hotel-session-888";

        // Turn 1: User says "10月1日入住，10月2日离开，我需要标准间，2人"
        RunRequest req1 = new RunRequest("10月1日入住，10月2日离开，我需要标准间，2人", workspace, conversationId);
        RunResult result1 = runner.chat(spec, req1);

        assertEquals(RunStatus.COMPLETED, result1.snapshot().status());
        assertTrue(result1.message().contains("请问入住客人的姓名是？"));

        // Turn 1 had empty history when opened
        assertEquals(0, capturedHistories.get(0).size());
        assertEquals("10月1日入住，10月2日离开，我需要标准间，2人", capturedGoals.get(0));

        // Memory now contains Turn 1 user message + assistant response
        List<ChatMessage> memAfterTurn1 = runner.memory().getHistory(conversationId);
        assertEquals(2, memAfterTurn1.size());
        assertEquals("10月1日入住，10月2日离开，我需要标准间，2人", memAfterTurn1.get(0).content());
        assertEquals(ChatMessage.Role.USER, memAfterTurn1.get(0).role());
        assertEquals("在为您查询房态前，还需要确认一项信息：请问入住客人的姓名是？", memAfterTurn1.get(1).content());
        assertEquals(ChatMessage.Role.ASSISTANT, memAfterTurn1.get(1).role());

        // Turn 2: User says "张鹏"
        RunRequest req2 = new RunRequest("张鹏", workspace, conversationId);
        RunResult result2 = runner.chat(spec, req2);

        assertEquals(RunStatus.COMPLETED, result2.snapshot().status());
        assertTrue(result2.message().contains("已成功为您预订10月1日标准间，入住客人张鹏！"));

        // Turn 2 opened session WITH Turn 1 history!
        assertEquals(2, capturedHistories.get(1).size());
        assertEquals("10月1日入住，10月2日离开，我需要标准间，2人", capturedHistories.get(1).get(0).content());
        assertEquals("在为您查询房态前，还需要确认一项信息：请问入住客人的姓名是？", capturedHistories.get(1).get(1).content());
        assertEquals("张鹏", capturedGoals.get(1));

        // Memory now contains all 4 messages
        List<ChatMessage> memAfterTurn2 = runner.memory().getHistory(conversationId);
        assertEquals(4, memAfterTurn2.size());
        assertEquals("张鹏", memAfterTurn2.get(2).content());
        assertEquals(ChatMessage.Role.USER, memAfterTurn2.get(2).role());
        assertEquals("已成功为您预订10月1日标准间，入住客人张鹏！", memAfterTurn2.get(3).content());
        assertEquals(ChatMessage.Role.ASSISTANT, memAfterTurn2.get(3).role());
    }

    @Test
    void testAgentConversationWrapperMultiTurnContinuity() {
        AtomicReference<List<ChatMessage>> lastHistory = new AtomicReference<>(List.of());

        ModelGateway mockGateway = new ModelGateway() {
            @Override
            public ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools) {
                return openSession(instructions, List.of(), goal, tools);
            }

            @Override
            public ModelSession openSession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
                lastHistory.set(history != null ? List.copyOf(history) : List.of());
                return new ModelSession() {
                    @Override
                    public ModelResponse nextResponse(Duration timeout) {
                        return new ModelResponse("Echo: " + goal, List.of(), ModelResponse.FinishReason.STOP, java.util.Optional.empty());
                    }
                    @Override public void appendToolResult(String callId, ToolOutcome outcome) {}
                    @Override public void appendUserMessage(String message) {}
                    @Override public void appendRuntimeFeedback(String feedback) {}
                    @Override public long estimatedInputTokens() { return 50; }
                    @Override public void close() {}
                };
            }
        };

        DefaultAgentRunner runner = new DefaultAgentRunner(mockGateway, new ToolCatalog(List.of()),
                eventSink);

        AgentConversation conversation = new AgentConversation(runner, spec, workspace, "conv-wrapper-test");
        assertEquals("conv-wrapper-test", conversation.conversationId());

        conversation.send("第一条消息");
        assertEquals(2, conversation.history().size());
        assertEquals(0, lastHistory.get().size());

        conversation.send("第二条消息");
        assertEquals(4, conversation.history().size());
        assertEquals(2, lastHistory.get().size());
        assertEquals("第一条消息", lastHistory.get().get(0).content());

        conversation.clearHistory();
        assertEquals(0, conversation.history().size());
    }
}
