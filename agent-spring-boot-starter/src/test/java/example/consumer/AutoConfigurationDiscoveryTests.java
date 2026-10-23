package example.consumer;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.bootstrap.ModelProperties;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.SpringAiModelGateway;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.trace.RunEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class AutoConfigurationDiscoveryTests {
    // No SDK @Import, AutoConfigurations.of, or component scan: discovery must use imports metadata.
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerConfiguration.class);

    @Test
    void externalConsumerDiscoversSdkWithoutScanningOrCredentials() {
        context.withPropertyValues("agent.cli.enabled=true").run(application -> {
            assertNull(application.getStartupFailure());
            assertNotNull(application.getBean(AgentProperties.class).limits());
            assertNotNull(application.getBean(AgentProperties.class).workspace());
            assertFalse(application.getBean(ModelProperties.class).isEnabled());
            assertTrue(application.getBean(ScenarioRegistry.class).entries().isEmpty());
            assertNotNull(application.getBean(ToolCatalog.class));
            assertNotNull(application.getBean(RunEventSink.class));
            assertTrue(application.getBeansOfType(ModelGateway.class).isEmpty());
            assertTrue(application.getBeansOfType(AgentRunner.class).isEmpty());
            assertTrue(application.getBeansOfType(ScenarioPlugin.class).isEmpty());
            assertTrue(application.getBeansOfType(ApplicationRunner.class).isEmpty());
            assertTrue(application.getBeansOfType(CommandLineRunner.class).isEmpty());
        });
    }

    @Test
    void discoveredModelAutoConfigurationPrecedesRunnerAutoConfiguration() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.api-key=local-test-key",
                        "agent.model.model=local-test-model")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertInstanceOf(SpringAiModelGateway.class, application.getBean(ModelGateway.class));
                    assertInstanceOf(DefaultAgentRunner.class, application.getBean(AgentRunner.class));
                });
    }

    @Test
    void consumerModelBeanCreatesRunnerWhileBuiltInModelIsDisabled() {
        context.withUserConfiguration(ConsumerModelConfiguration.class)
                .withPropertyValues("agent.model.enabled=false")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertEquals(1, application.getBeansOfType(ModelGateway.class).size());
                    assertSame(application.getBean("consumerModel"), application.getBean(ModelGateway.class));
                    assertInstanceOf(DefaultAgentRunner.class, application.getBean(AgentRunner.class));
                    assertFalse(application.getBean(ModelProperties.class).isEnabled());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerModelConfiguration {
        @Bean
        ModelGateway consumerModel() {
            return (instructions, goal, tools) -> {
                throw new AssertionError("Context startup must not invoke the model");
            };
        }
    }
}
