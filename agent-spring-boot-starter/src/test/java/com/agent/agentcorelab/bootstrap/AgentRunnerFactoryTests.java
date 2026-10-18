package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentRunnerFactoryTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentConfiguration.class, ModelConfiguration.class));

    @Test
    void disabledModelDoesNotCreateAnUnusableFactory() {
        context.run(application -> assertTrue(application.getBeansOfType(AgentRunnerFactory.class).isEmpty()));
    }

    @Test
    void factoryRunnersDoNotBlockOrCancelOtherWaitingSessions(@TempDir Path root) {
        context.withBean(ModelGateway.class, AgentRunnerFactoryTests::clarifyingModel)
                .withPropertyValues("agent.workspace.input-root=" + root.resolve("input"),
                        "agent.workspace.output-root=" + root.resolve("output"),
                        "agent.workspace.trace-root=" + root.resolve("traces"),
                        "agent.workspace.create-input-if-missing=true")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertFalse(Files.exists(root.resolve("input")));
                    var properties = application.getBean(AgentProperties.class);
                    assertTrue(properties.workspace().createInputIfMissing());
                    var spec = new AgentSpec("session-test", "Ask once, then finish", Set.of(ClarificationTool.NAME),
                            properties.limits(), (run, answer) -> new CompletionCheck.Result(true, ""));
                    var factory = application.getBean(AgentRunnerFactory.class);
                    try (var first = factory.create(); var second = factory.create()) {
                        assertNotSame(first, second);
                        assertNotSame(first, application.getBean(AgentRunner.class));
                        var waitingA = first.run(spec, new RunRequest("first", properties.workspace()));
                        var waitingB = second.run(spec, new RunRequest("second", properties.workspace()));
                        assertEquals(RunStatus.WAITING_INPUT, waitingA.snapshot().status());
                        assertEquals(RunStatus.WAITING_INPUT, waitingB.snapshot().status());
                        assertThrows(IllegalStateException.class,
                                () -> first.run(spec, new RunRequest("overlapping", properties.workspace())));
                        assertEquals(0, waitingA.snapshot().observations().size());
                        assertEquals(1, waitingA.snapshot().metrics().toolCalls());
                        first.close();
                        assertEquals(RunStatus.CANCELLED, first.snapshot(waitingA.snapshot().runId()).status());
                        assertEquals(RunStatus.WAITING_INPUT, second.snapshot(waitingB.snapshot().runId()).status());
                        var complete = second.resume(waitingB.snapshot().runId(), "answer");
                        assertEquals(RunStatus.COMPLETED, complete.snapshot().status());
                        assertEquals(2, complete.snapshot().metrics().modelRequests());
                        assertEquals(1, complete.snapshot().metrics().toolCalls());
                        assertEquals(1, complete.snapshot().observations().size());
                        assertEquals(ClarificationTool.NAME, complete.snapshot().observations().getFirst().call().name());
                        assertInstanceOf(ToolOutcome.Success.class, complete.snapshot().observations().getFirst().outcome());
                    }
                });
    }

    @Test
    void consumerFactoryIsRespectedAndCanSupplySingletonWithoutBuiltInModel() {
        AtomicInteger created = new AtomicInteger();
        DefaultAgentRunner supplied = mock(DefaultAgentRunner.class);
        AgentRunnerFactory factory = () -> {
            created.incrementAndGet();
            return supplied;
        };
        context.withBean(AgentRunnerFactory.class, () -> factory).run(application -> {
            assertNull(application.getStartupFailure());
            assertSame(factory, application.getBean(AgentRunnerFactory.class));
            assertSame(supplied, application.getBean(AgentRunner.class));
            assertEquals(1, created.get());
            assertTrue(application.getBeansOfType(ModelGateway.class).isEmpty());
        });
    }

    @Test
    void inputCreationDefaultsToFalse() {
        context.run(application -> assertFalse(application.getBean(AgentProperties.class).workspace().createInputIfMissing()));
    }

    private static ModelGateway clarifyingModel() {
        return (instructions, goal, tools) -> new ModelSession() {
            private boolean answered;
            private boolean closed;

            @Override
            public ModelResponse nextResponse(Duration timeout) {
                assertFalse(closed);
                return answered
                        ? new ModelResponse("Completed", List.of(), ModelResponse.FinishReason.STOP, Optional.empty())
                        : new ModelResponse("", List.of(new ToolCall("question", ClarificationTool.NAME,
                                "{\"question\":\"Which target?\"}")), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty());
            }

            @Override
            public void appendToolResult(String callId, ToolOutcome outcome) {
                assertFalse(closed);
                assertEquals("question", callId);
                assertInstanceOf(ToolOutcome.Success.class, outcome);
                answered = true;
            }

            @Override public void appendUserMessage(String message) { fail("Clarification uses the tool result"); }
            @Override public void appendRuntimeFeedback(String feedback) { fail("No repair expected"); }
            @Override public long estimatedInputTokens() { return 100; }
            @Override public void close() { closed = true; }
        };
    }
}
