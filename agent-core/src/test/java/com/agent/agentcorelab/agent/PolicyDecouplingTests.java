package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.policy.ActiveTaskConflictException;
import com.agent.agentcorelab.agent.policy.ActiveTaskPolicy;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.workspace.Workspace;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PolicyDecouplingTests {

    @TempDir
    Path root;
    private Workspace workspace;
    private ScriptModelSession session;
    private String capturedInstructions;
    private DefaultAgentRunner runner;
    private RunLimits limits;
    private CompletionCheck completion;

    @BeforeEach
    void setUp() throws IOException {
        workspace = new Workspace(Files.createDirectory(root.resolve("input")), root.resolve("output"), root.resolve("traces"));
        session = new ScriptModelSession();
        limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
        completion = (run, answer) -> new CompletionCheck.Result(true, "");
        ModelGateway gateway = (instructions, goal, tools) -> {
            capturedInstructions = instructions;
            return session;
        };
        Tool clarification = new ClarificationTool();
        Tool dummyTool = new DummyTool();
        ToolCatalog catalog = new ToolCatalog(List.of(clarification, dummyTool));
        runner = new DefaultAgentRunner(gateway, catalog, event -> {});
    }

    @Test
    void rejectPolicyThrowsActiveTaskConflictException() {
        AgentSpec spec = new AgentSpec("strict-agent", "Do strict task", Set.of("dummy", ClarificationTool.NAME), limits, completion, ExecutionPolicy.strict());
        session.add(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"Which repo?\"}")));

        RunResult result1 = runner.run(spec, new RunRequest("Task 1", workspace));
        assertEquals(RunStatus.WAITING_INPUT, result1.snapshot().status());
        UUID run1Id = result1.snapshot().runId();

        ActiveTaskConflictException ex = assertThrows(ActiveTaskConflictException.class,
                () -> runner.run(spec, new RunRequest("Task 2", workspace)));

        assertEquals(run1Id, ex.activeRunId());
        assertEquals(RunStatus.WAITING_INPUT, ex.activeStatus());
        assertTrue(ex.pendingQuestion().isPresent());
        assertEquals("Which repo?", ex.pendingQuestion().get().question());
        assertTrue(ex instanceof IllegalStateException);
    }

    @Test
    void resumeIfWaitingPolicySeamlesslyResumesClarification() {
        AgentSpec spec = new AgentSpec("chat-agent", "Help user", Set.of("dummy", ClarificationTool.NAME), limits, completion, ExecutionPolicy.conversational());
        session.add(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"Choose A or B\"}")));

        RunResult result1 = runner.run(spec, new RunRequest("Analyze issue", workspace));
        assertEquals(RunStatus.WAITING_INPUT, result1.snapshot().status());
        UUID originalRunId = result1.snapshot().runId();

        // Model responds to the clarification answer
        session.add(text("Issue resolved for choice A"));

        // User submits "Choice A" using run() directly with conversational policy
        RunResult result2 = runner.run(spec, new RunRequest("Choice A", workspace), ExecutionPolicy.conversational());

        assertEquals(RunStatus.COMPLETED, result2.snapshot().status());
        assertEquals(originalRunId, result2.snapshot().runId(), "Should seamlessly continue the same runId");
        assertEquals("Issue resolved for choice A", result2.message());
        assertEquals(1, session.results.size());
        assertTrue(session.results.get(0).outcome() instanceof ToolOutcome.Success);
    }

    @Test
    void preemptIfWaitingPolicyAbortsOldTaskAndStartsNewTask() {
        AgentSpec spec = new AgentSpec("task-agent", "Execute tasks", Set.of("dummy", ClarificationTool.NAME), limits, completion, ExecutionPolicy.preemptive());
        session.add(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"Choose warehouse\"}")));

        RunResult result1 = runner.run(spec, new RunRequest("Task 1 goal", workspace));
        assertEquals(RunStatus.WAITING_INPUT, result1.snapshot().status());
        UUID run1Id = result1.snapshot().runId();

        // User changes mind and sends a totally new goal
        session.add(text("Finished task 2"));
        RunResult result2 = runner.run(spec, new RunRequest("Task 2: check GMV instead", workspace), ExecutionPolicy.preemptive());

        assertEquals(RunStatus.COMPLETED, result2.snapshot().status());
        assertNotEquals(run1Id, result2.snapshot().runId(), "Task 2 must have a new runId");
        assertEquals("Finished task 2", result2.message());

        // Verify task 1 snapshot was cleanly marked as CANCELLED with PREEMPTED reason
        RunSnapshot snapshot1 = runner.snapshot(run1Id);
        assertEquals(RunStatus.CANCELLED, snapshot1.status());
    }

    @Test
    void agentConversationProvidesNaturalMultiTurnExperience() {
        AgentSpec spec = new AgentSpec("copilot-agent", "You are an IT copilot", Set.of("dummy", ClarificationTool.NAME), limits, completion);
        AgentConversation conversation = new AgentConversation(runner, spec, workspace);

        // Turn 1: user asks general question -> answers immediately
        session.add(text("I can help troubleshoot IT issues."));
        RunResult turn1 = conversation.send("你可以干什么");
        assertEquals(RunStatus.COMPLETED, turn1.snapshot().status());
        assertEquals("I can help troubleshoot IT issues.", turn1.message());

        // Turn 2: user asks specific question -> agent asks clarification
        session.add(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"请问是华东仓还是华南仓？\"}")));
        RunResult turn2 = conversation.send("退货率怎么涨了");
        assertEquals(RunStatus.WAITING_INPUT, turn2.snapshot().status());
        assertEquals("请问是华东仓还是华南仓？", turn2.message());

        // Turn 3: user simply sends option string -> agent resumes and completes!
        session.add(text("华东仓退货率上涨分析完成：因包装受潮导致。"));
        RunResult turn3 = conversation.send("华东仓");
        assertEquals(RunStatus.COMPLETED, turn3.snapshot().status());
        assertEquals("华东仓退货率上涨分析完成：因包装受潮导致。", turn3.message());
        assertEquals(turn2.snapshot().runId(), turn3.snapshot().runId());
    }

    @Test
    void customRuntimeRulesAreInjectedCorrectly() {
        String customRules = "Custom Rule: Always speak like a pirate.";
        ExecutionPolicy customPolicy = new ExecutionPolicy(ActiveTaskPolicy.REJECT, customRules);
        AgentSpec spec = new AgentSpec("pirate-agent", "Pirate instructions", Set.of("dummy"), limits, completion, customPolicy);

        session.add(text("Ahoy matey!"));
        RunResult result = runner.run(spec, new RunRequest("Say hello", workspace));

        assertEquals(RunStatus.COMPLETED, result.snapshot().status());
        assertNotNull(capturedInstructions);
        assertTrue(capturedInstructions.contains(customRules));
        assertTrue(capturedInstructions.contains("Pirate instructions"));
    }

    @Test
    void clarificationSubDialogueAllowsExplanationWithoutTriggeringIncompleteDeliverable() {
        // Enforce that "dummy" tool must have been executed to pass completion check (like SQL query required)
        CompletionCheck strictCheck = (run, answer) -> new CompletionCheck.Result(
                run.observations().stream().anyMatch(o -> o.call().name().equals("dummy")),
                "Must execute dummy tool to obtain evidence");

        AgentSpec spec = new AgentSpec("analysis-copilot", "Analyze metrics", Set.of("dummy", ClarificationTool.NAME),
                limits, strictCheck, ExecutionPolicy.conversational());

        // Step 1: User asks "Why did return rate decrease?" -> Model asks for clarification on date range
        session.add(calls(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"请问时间范围是本周还是本月？\"}")));
        RunResult step1 = runner.run(spec, new RunRequest("退货率怎么降低了", workspace));
        assertEquals(RunStatus.WAITING_INPUT, step1.snapshot().status());
        assertEquals("请问时间范围是本周还是本月？", step1.message());

        // Step 2: User responds with meta-question "为什么" (Why do you need this?)
        // Model responds with text explaining WHY it needs date range, without calling tools yet.
        session.add(text("因为退货率的统计需要明确分子分母的匹配周期，如果时间不明确会导致误判。"));
        RunResult step2 = runner.run(spec, new RunRequest("为什么", workspace), ExecutionPolicy.conversational());

        // Critical assertion: It must NOT fail with INCOMPLETE_DELIVERABLE!
        // It should gracefully stay in WAITING_INPUT with the explanation presented to the user!
        assertEquals(RunStatus.WAITING_INPUT, step2.snapshot().status());
        assertEquals("因为退货率的统计需要明确分子分母的匹配周期，如果时间不明确会导致误判。", step2.message());
        assertTrue(step2.snapshot().pendingQuestion().isPresent());
        assertEquals("因为退货率的统计需要明确分子分母的匹配周期，如果时间不明确会导致误判。", step2.snapshot().pendingQuestion().get().question());

        // Step 3: User now understands why and answers: "本月"
        // Model executes the required "dummy" tool and delivers the report
        session.add(calls(new ToolCall("d1", "dummy", "{}")));
        session.add(text("已按本月数据完成分析报告：退货率实际并未降低，而是上涨了。"));
        RunResult step3 = runner.run(spec, new RunRequest("本月", workspace), ExecutionPolicy.conversational());

        assertEquals(RunStatus.COMPLETED, step3.snapshot().status());
        assertEquals("已按本月数据完成分析报告：退货率实际并未降低，而是上涨了。", step3.message());
        assertEquals(2, step3.snapshot().observations().size());
        assertTrue(step3.snapshot().observations().stream().anyMatch(o -> o.call().name().equals("dummy")));
    }

    private static ModelResponse text(String message) {
        return new ModelResponse(message, List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
    }

    private static ModelResponse calls(ToolCall... toolCalls) {
        return new ModelResponse("", List.of(toolCalls), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
    }

    private static final class ScriptModelSession implements ModelSession {
        private final ArrayDeque<ModelResponse> responses = new ArrayDeque<>();
        private final List<Result> results = new ArrayList<>();
        private boolean closed;

        void add(ModelResponse response) {
            responses.add(response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            ModelResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("Script ran out of responses");
            }
            return response;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            results.add(new Result(callId, outcome));
        }

        @Override public void appendUserMessage(String message) {}
        @Override public void appendRuntimeFeedback(String feedback) {}
        @Override public long estimatedInputTokens() { return 100; }
        @Override public void close() { closed = true; }
    }

    private record Result(String callId, ToolOutcome outcome) {}

    private static final class DummyTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("dummy", "Dummy tool", "{\"type\":\"object\"}");
        }

        @Override
        public ToolOutcome execute(String argumentsJson, ToolContext context) {
            return new ToolOutcome.Success("{\"ok\":true}");
        }
    }
}
