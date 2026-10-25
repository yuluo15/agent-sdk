package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.CandidateTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolAssemblyException;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCandidatePool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MultiAgentToolPoolBootstrapTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentConfiguration.class, ModelConfiguration.class));

    @Test
    void testToolCandidatePoolBeanCreatedAndWiresCustomCandidateTools() {
        context.withUserConfiguration(CustomToolsConfiguration.class)
                .run(app -> {
                    assertNull(app.getStartupFailure());

                    ToolCandidatePool pool = app.getBean(ToolCandidatePool.class);
                    assertNotNull(pool);

                    ToolCatalog catalog = app.getBean(ToolCatalog.class);
                    assertNotNull(catalog);

                    // Candidate tools and normal tools should both be in the pool
                    assertTrue(pool.find("publicFaqTool").isPresent());
                    assertTrue(pool.find("adminTool").isPresent());
                    assertTrue(pool.find("tenantRestrictedTool").isPresent());

                    // Built-in tools are registered
                    assertTrue(pool.find(com.agent.agentcorelab.tool.builtin.ClarificationTool.NAME).isPresent());
                    assertTrue(pool.find(com.agent.agentcorelab.tool.builtin.UpdatePlanTool.NAME).isPresent());

                    CandidateTool adminCandidate = pool.find("adminTool").orElseThrow();
                    assertEquals("finance", adminCandidate.domain());
                    assertEquals("FIN_ADMIN", adminCandidate.requiredRole());

                    CandidateTool tenantCandidate = pool.find("tenantRestrictedTool").orElseThrow();
                    assertTrue(tenantCandidate.allowedTenants().contains("tenant-corp"));
                });
    }

    @Test
    void testAgentRunnerFactoryUsesCandidatePoolWithLeastPrivilegeAssembly(@TempDir Path root) {
        MockModelSession session = new MockModelSession();

        context.withUserConfiguration(CustomToolsConfiguration.class)
                .withBean(ModelGateway.class, () -> (instructions, goal, tools) -> session)
                .withPropertyValues(
                        "agent.workspace.input-root=" + root.resolve("input"),
                        "agent.workspace.output-root=" + root.resolve("output"),
                        "agent.workspace.trace-root=" + root.resolve("traces"),
                        "agent.workspace.create-input-if-missing=true"
                )
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    AgentRunnerFactory factory = app.getBean(AgentRunnerFactory.class);
                    assertNotNull(factory);

                    AgentProperties properties = app.getBean(AgentProperties.class);

                    // 1. Unprivileged Agent Spec: only publicFaqTool declared
                    AgentSpec unprivilegedAgent = new AgentSpec(
                            "faq-agent",
                            "Answer questions",
                            Set.of("publicFaqTool"),
                            properties.limits(),
                            (s, a) -> CompletionCheck.Result.satisfied()
                    );

                    AgentRunner runner = factory.create();
                    assertTrue(runner instanceof DefaultAgentRunner);
                    DefaultAgentRunner defaultRunner = (DefaultAgentRunner) runner;
                    assertNotNull(defaultRunner.candidatePool());

                    // When running unprivileged agent, model tries to invoke adminTool
                    session.enqueue(new ModelResponse("", List.of(
                            new ToolCall("call_1", "adminTool", "{}")
                    ), ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));

                    RunRequest req = new RunRequest("Transfer money", properties.workspace());
                    RunResult result = runner.run(unprivilegedAgent, req);

                    // adminTool must NEVER execute
                    assertEquals(0, CustomToolsConfiguration.adminExecutions.get());
                    // Model receives UNKNOWN_TOOL error because adminTool was not in catalog
                    assertFalse(session.appendedResults.isEmpty());
                    ToolOutcome outcome = session.appendedResults.get(0);
                    assertTrue(outcome instanceof ToolOutcome.Failure);
                    assertEquals("UNKNOWN_TOOL", ((ToolOutcome.Failure) outcome).code());

                    // 2. Tenant isolation verification with AgentRunner
                    AgentSpec tenantAgent = new AgentSpec(
                            "corp-agent",
                            "Corporate ops",
                            Set.of("tenantRestrictedTool"),
                            properties.limits(),
                            (s, a) -> CompletionCheck.Result.satisfied()
                    );

                    AgentRunner runner2 = factory.create();
                    // Unauthorized tenant -> throws ToolAssemblyException
                    TrustedContext foreignTenantCtx = new TrustedContext("foreign-corp", "user1", "agent1", Set.of());
                    RunRequest foreignReq = new RunRequest("Do corp ops", properties.workspace(), () -> false,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(foreignTenantCtx));

                    ToolAssemblyException ex = assertThrows(ToolAssemblyException.class,
                            () -> runner2.run(tenantAgent, foreignReq));
                    assertEquals("TENANT_ISOLATION_VIOLATION", ex.violationCode());

                    // Authorized tenant -> successful run
                    TrustedContext authorizedCtx = new TrustedContext("tenant-corp", "user1", "agent1", Set.of());
                    RunRequest authorizedReq = new RunRequest("Do corp ops", properties.workspace(), () -> false,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(authorizedCtx));

                    assertDoesNotThrow(() -> runner2.run(tenantAgent, authorizedReq));
                });
    }

    // =========================================================================
    // Test Configurations & Tools
    // =========================================================================

    @Configuration
    static class CustomToolsConfiguration {
        static final AtomicInteger adminExecutions = new AtomicInteger();

        @Bean
        public Tool publicFaqTool() {
            return new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition("publicFaqTool", "Search FAQ", "{\"type\":\"object\"}");
                }

                @Override
                public ToolOutcome execute(String argumentsJson, ToolContext context) {
                    return new ToolOutcome.Success("{\"result\":\"faq answer\"}");
                }
            };
        }

        @Bean
        public CandidateTool adminTool() {
            Tool tool = new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition("adminTool", "Admin tool", "{\"type\":\"object\"}");
                }

                @Override
                public ToolOutcome execute(String argumentsJson, ToolContext context) {
                    adminExecutions.incrementAndGet();
                    return new ToolOutcome.Success("{\"admin\":\"done\"}");
                }
            };
            return CandidateTool.of(tool, "finance", Set.of("admin", "finance"), "FIN_ADMIN");
        }

        @Bean
        public CandidateTool tenantRestrictedTool() {
            Tool tool = new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition("tenantRestrictedTool", "Tenant restricted tool", "{\"type\":\"object\"}");
                }

                @Override
                public ToolOutcome execute(String argumentsJson, ToolContext context) {
                    return new ToolOutcome.Success("{\"status\":\"ok\"}");
                }
            };
            return CandidateTool.of(tool, "enterprise", Set.of(), "", Set.of("tenant-corp"));
        }
    }

    private static final class MockModelSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        final List<ToolOutcome> appendedResults = new ArrayList<>();

        void enqueue(ModelResponse response) {
            responses.add(response);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            ModelResponse r = responses.poll();
            if (r == null) {
                return new ModelResponse("Default mock completion.", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
            }
            return r;
        }

        @Override
        public long estimatedInputTokens() {
            return 100;
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            appendedResults.add(outcome);
        }

        @Override
        public void appendUserMessage(String message) {
        }

        @Override
        public void appendRuntimeFeedback(String value) {
        }

        @Override
        public void close() {
        }
    }
}
