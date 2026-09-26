package com.agent.agentcorelab.cli;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.RunLimits;
import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.bootstrap.CliConfiguration;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ResourceLock("SYSTEM_IN")
@ResourceLock("SYSTEM_OUT")
class CliConfigurationTests {
    private final RunLimits limits = new RunLimits(12, 20, Duration.ofSeconds(30), Duration.ofMinutes(2));
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CliConfiguration.class)
            .withBean(AgentProperties.class, () -> new AgentProperties(limits,
                    new Workspace(Path.of("samples/input"), Path.of("output"), Path.of("traces"))))
            .withBean(ScenarioRegistry.class, () -> new ScenarioRegistry(List.of(
                    AgentCliTests.scenario("file-inspection"), AgentCliTests.scenario("other")), limits));

    @Test
    void cliIsAbsentByDefaultAndWhenExplicitlyDisabled() {
        context.run(application -> assertEquals(0, application.getBeansOfType(ApplicationRunner.class).size()));
        context.withPropertyValues("agent.cli.enabled=false")
                .run(application -> assertEquals(0, application.getBeansOfType(ApplicationRunner.class).size()));
    }

    @Test
    void enabledCliDefaultsToFileInspectionAndKeepsScenariosAvailableWithoutModel() {
        context.withPropertyValues("agent.cli.enabled=true").run(application -> {
            assertNull(application.getStartupFailure());
            String output = run(application.getBean(ApplicationRunner.class), "/scenarios\n目标\n/exit\n");
            assertTrue(output.contains("当前场景：file-inspection"));
            assertTrue(output.contains("other — 本地测试场景 other"));
            assertTrue(output.contains("agent.model.enabled=true"));
            assertFalse(output.contains("状态=COMPLETED"));
        });
    }

    @Test
    void enabledModelWithoutRunnerReportsConfigurationRatherThanFakeCompletion() {
        context.withPropertyValues("agent.cli.enabled=true", "agent.model.enabled=true")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    String output = run(application.getBean(ApplicationRunner.class), "/scenarios\n目标\n/exit\n");
                    assertTrue(output.contains("AgentRunner bean"));
                    assertTrue(output.contains("file-inspection — 本地测试场景"));
                    assertFalse(output.contains("任务已提交"));
                });
    }

    @Test
    void modelDisabledNeverUsesAvailableRunnerAndScenarioPropertyIsHonored() {
        AgentRunner runner = mock(AgentRunner.class);
        context.withPropertyValues("agent.cli.enabled=true", "agent.cli.scenario=other", "agent.model.enabled=false")
                .withBean(AgentRunner.class, () -> runner).run(application -> {
                    String output = run(application.getBean(ApplicationRunner.class), "目标\n/exit\n");
                    assertTrue(output.contains("当前场景：other"));
                    assertTrue(output.contains("模型配置缺失"));
                    verifyNoInteractions(runner);
                });
    }

    @Test
    void terminalReaderAndWriterUseUtf8() {
        context.withPropertyValues("agent.cli.enabled=true").run(application -> {
            // Full-width spaces only strip correctly when the terminal decodes the UTF-8 input.
            String output = run(application.getBean(ApplicationRunner.class),
                    "/scenario \u3000other\u3000\n/exit\n");
            assertTrue(output.contains("当前场景：other"));
            assertFalse(output.contains("场景不存在"));
        });
    }

    private static String run(ApplicationRunner runner, String commands) throws Exception {
        var previousIn = System.in;
        var previousOut = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var terminal = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setIn(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)));
            System.setOut(terminal);
            runner.run(new DefaultApplicationArguments(new String[0]));
        } finally {
            System.setIn(previousIn);
            System.setOut(previousOut);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
