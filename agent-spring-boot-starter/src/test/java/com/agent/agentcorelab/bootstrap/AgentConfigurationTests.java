package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.SpringAiModelGateway;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentConfigurationTests {
    // Deliberately list agent first: auto-configuration ordering must still supply the model first.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentConfiguration.class, ModelConfiguration.class));

    @Test
    void defaultsStartWithoutCredentialsOrApplicationProperties() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            AgentProperties properties = application.getBean(AgentProperties.class);
            assertEquals(defaultLimits(), properties.limits());
            assertEquals(Path.of("workspace/input"), properties.workspace().inputRoot().normalize());
            assertEquals(Path.of("workspace/output"), properties.workspace().outputRoot().normalize());
            assertEquals(Path.of("workspace/traces"), properties.workspace().traceRoot().normalize());
            ModelProperties model = application.getBean(ModelProperties.class);
            assertFalse(model.isEnabled());
            assertNull(model.getApiKey());
            assertNull(model.getModel());
            assertEquals("https://api.openai.com/v1", model.getBaseUrl());
            assertEquals(4096, model.getMaxOutputTokens());
            ScenarioRegistry scenarios = application.getBean(ScenarioRegistry.class);
            assertTrue(scenarios.entries().isEmpty());
            assertSame(scenarios.tools(), application.getBean(ToolCatalog.class));
            assertEquals(1, application.getBeansOfType(RunEventSink.class).size());
            assertInstanceOf(JsonlRunEventSink.class, application.getBean(RunEventSink.class));
            assertTrue(application.getBeansOfType(ModelGateway.class).isEmpty());
            assertTrue(application.getBeansOfType(AgentRunner.class).isEmpty());
            assertTrue(application.getBeansOfType(ScenarioPlugin.class).isEmpty());
            assertTrue(application.getBeansOfType(ApplicationRunner.class).isEmpty());
            assertTrue(application.getBeansOfType(CommandLineRunner.class).isEmpty());
        });
    }

    @Test
    void partialLimitOverridesRetainEveryOtherDefault() {
        context.withPropertyValues("agent.limits.max-tool-calls=7", "agent.limits.model-timeout=2s")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertEquals(new RunLimits(16, 7, Duration.ofSeconds(2), Duration.ofSeconds(300),
                            32768, 4096, 3, 3, 16384), application.getBean(AgentProperties.class).limits());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "max-model-requests=0", "max-tool-calls=-1", "model-timeout=0s", "active-timeout=-1s",
            "max-context-tokens=0", "output-token-reserve=0", "output-token-reserve=32768",
            "max-repeated-tool-calls=1", "max-consecutive-tool-errors=0", "max-tool-result-bytes=255"
    })
    void invalidLimitsFailInsteadOfBeingRepaired(String invalidLimit) {
        context.withPropertyValues("agent.limits." + invalidLimit).run(application -> {
            Throwable failure = application.getStartupFailure();
            assertNotNull(failure);
            while (failure.getCause() != null) {
                failure = failure.getCause();
            }
            assertInstanceOf(IllegalArgumentException.class, failure);
            assertTrue(failure.getMessage().equals("Timeouts must be positive")
                    || failure.getMessage().equals("Request, context and tool limits must be valid positive values"));
        });
    }

    @Test
    void directConstructorPreservesCoreObjects() {
        RunLimits limits = defaultLimits();
        Workspace workspace = new Workspace(Path.of("input"), Path.of("output"), Path.of("traces"));
        AgentProperties properties = new AgentProperties(limits, workspace);
        assertSame(limits, properties.limits());
        assertSame(workspace, properties.workspace());
    }

    @Test
    void configuredTraceRootIsUsedAndOtherWorkspaceDefaultsSurvive(@TempDir Path directory) throws Exception {
        Path traceRoot = directory.toRealPath();
        UUID runId = UUID.randomUUID();
        context.withPropertyValues("agent.workspace.trace-root=" + traceRoot).run(application -> {
            assertNull(application.getStartupFailure());
            Workspace workspace = application.getBean(AgentProperties.class).workspace();
            assertEquals(Path.of("workspace/input"), workspace.inputRoot().normalize());
            assertEquals(Path.of("workspace/output"), workspace.outputRoot().normalize());
            assertEquals(traceRoot, workspace.traceRoot());
            application.getBean(RunEventSink.class).publish(new RunEvent(1, runId, Instant.EPOCH,
                    RunEvent.Type.RUN_STARTED, "{}"));
        });
        assertTrue(Files.readString(traceRoot.resolve(runId + ".jsonl")).contains("RUN_STARTED"));
    }

    @Test
    void explicitlyDisabledModelProvidesNeitherGatewayNorRunner() {
        context.withPropertyValues("agent.model.enabled=false", "agent.model.max-output-tokens=99999")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertTrue(application.getBeansOfType(ModelGateway.class).isEmpty());
                    assertTrue(application.getBeansOfType(AgentRunner.class).isEmpty());
                    assertNotNull(application.getBean(ScenarioRegistry.class));
                    assertNotNull(application.getBean(RunEventSink.class));
                });
    }

    @Test
    void enabledModelCreatesGatewayBeforeRunnerWithoutMakingRequests() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.api-key=local-test-key",
                        "agent.model.model=local-test-model")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertInstanceOf(SpringAiModelGateway.class, application.getBean(ModelGateway.class));
                    assertInstanceOf(DefaultAgentRunner.class, application.getBean(AgentRunner.class));
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppliedGatewayCreatesRunnerAndBypassesBuiltInValidation(boolean enabled) {
        ModelGateway model = mock(ModelGateway.class);
        context.withPropertyValues("agent.model.enabled=" + enabled, "agent.model.max-output-tokens=99999")
                .withBean(ModelGateway.class, () -> model).run(application -> {
                    assertNull(application.getStartupFailure());
                    assertEquals(1, application.getBeansOfType(ModelGateway.class).size());
                    assertSame(model, application.getBean(ModelGateway.class));
                    assertInstanceOf(DefaultAgentRunner.class, application.getBean(AgentRunner.class));
                    verifyNoInteractions(model);
                });
    }

    @Test
    void allSuppliedSdkBeansAreRespected() {
        ScenarioRegistry scenarios = new ScenarioRegistry(List.of(), defaultLimits());
        ToolCatalog tools = new ToolCatalog(List.of());
        RunEventSink events = mock(RunEventSink.class);
        ModelGateway model = mock(ModelGateway.class);
        AgentRunner runner = mock(AgentRunner.class);
        context.withBean(ScenarioRegistry.class, () -> scenarios)
                .withBean(ToolCatalog.class, () -> tools)
                .withBean(RunEventSink.class, () -> events)
                .withBean(ModelGateway.class, () -> model)
                .withBean(AgentRunner.class, () -> runner)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertEquals(1, application.getBeansOfType(ScenarioRegistry.class).size());
                    assertEquals(1, application.getBeansOfType(ToolCatalog.class).size());
                    assertEquals(1, application.getBeansOfType(RunEventSink.class).size());
                    assertEquals(1, application.getBeansOfType(ModelGateway.class).size());
                    assertEquals(1, application.getBeansOfType(AgentRunner.class).size());
                    assertSame(scenarios, application.getBean(ScenarioRegistry.class));
                    assertSame(tools, application.getBean(ToolCatalog.class));
                    assertSame(events, application.getBean(RunEventSink.class));
                    assertSame(model, application.getBean(ModelGateway.class));
                    assertSame(runner, application.getBean(AgentRunner.class));
                    assertTrue(application.getBeansOfType(DefaultAgentRunner.class).isEmpty());
                    verifyNoInteractions(events, model, runner);
                });
    }

    @Test
    void suppliedRunnerDoesNotRequireAnyModel() {
        AgentRunner runner = mock(AgentRunner.class);
        context.withBean(AgentRunner.class, () -> runner).run(application -> {
            assertNull(application.getStartupFailure());
            assertSame(runner, application.getBean(AgentRunner.class));
            assertTrue(application.getBeansOfType(ModelGateway.class).isEmpty());
        });
    }

    @Test
    void suppliedRegistryIsAlsoTheSourceOfDefaultTools() {
        ScenarioRegistry scenarios = new ScenarioRegistry(List.of(scenario("provided")), defaultLimits());
        context.withBean(ScenarioRegistry.class, () -> scenarios)
                .withBean(ScenarioPlugin.class, () -> scenario("not-registered"))
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertSame(scenarios, application.getBean(ScenarioRegistry.class));
                    assertSame(scenarios.tools(), application.getBean(ToolCatalog.class));
                    assertEquals(List.of("provided"), scenarios.entries().stream().map(e -> e.spec().id()).toList());
                });
    }

    @Test
    void registryContainsOnlyUserScenariosAndNoCliEvenIfItsFlagIsEnabled() {
        ScenarioPlugin scenario = scenario("consumer-scenario");
        context.withBean(ScenarioPlugin.class, () -> scenario)
                .withPropertyValues("agent.cli.enabled=true")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    ScenarioRegistry registry = application.getBean(ScenarioRegistry.class);
                    assertEquals(List.of("consumer-scenario"), registry.entries().stream().map(e -> e.spec().id()).toList());
                    assertSame(scenario, application.getBean(ScenarioPlugin.class));
                    assertEquals(1, application.getBeansOfType(ScenarioPlugin.class).size());
                    assertEquals(defaultLimits(), registry.require("consumer-scenario").spec().limits());
                    assertEquals(List.of(ClarificationTool.NAME), application.getBean(ToolCatalog.class)
                            .definitions().stream().map(definition -> definition.name()).toList());
                    assertTrue(application.getBeansOfType(ApplicationRunner.class).isEmpty());
                    assertTrue(application.getBeansOfType(CommandLineRunner.class).isEmpty());
                });
    }

    private static RunLimits defaultLimits() {
        return new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300),
                32768, 4096, 3, 3, 16384);
    }

    private static ScenarioPlugin scenario(String id) {
        return new ScenarioPlugin() {
            @Override public String id() { return id; }
            @Override public String description() { return "Consumer scenario"; }
            @Override public String instructions() { return "Consumer instructions"; }
            @Override public List<Tool> tools() { return List.of(); }
            @Override public CompletionCheck completionCheck() {
                return (run, answer) -> new CompletionCheck.Result(true, "");
            }
        };
    }
}
