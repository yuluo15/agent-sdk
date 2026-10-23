package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.SessionRunnerManager;
import com.agent.agentcorelab.model.ModelGateway;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SessionRunnerManagerSpringBootTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentConfiguration.class, ModelConfiguration.class));

    @Test
    void sessionRunnerManagerBeanIsCreatedWhenFactoryIsPresent() {
        DefaultAgentRunner runnerMock = mock(DefaultAgentRunner.class);
        AgentRunnerFactory factory = () -> runnerMock;
        context.withBean(AgentRunnerFactory.class, () -> factory)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    SessionRunnerManager manager = application.getBean(SessionRunnerManager.class);
                    assertNotNull(manager);
                    assertEquals(0, manager.sessionCount());

                    // Backwards compatibility: singleton AgentRunner bean is still available
                    AgentRunner runner = application.getBean(AgentRunner.class);
                    assertNotNull(runner);
                });
    }

    @Test
    void bindsMicrometerMetricsWhenRegistryIsAvailable() {
        AgentRunner runnerMock = mock(DefaultAgentRunner.class);
        AgentRunnerFactory factory = () -> (DefaultAgentRunner) runnerMock;
        MeterRegistry registry = new SimpleMeterRegistry();

        context.withBean(AgentRunnerFactory.class, () -> factory)
                .withBean(MeterRegistry.class, () -> registry)
                .withPropertyValues("agent.session.max-sessions=50", "agent.session.idle-timeout=10m")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    SessionRunnerManager manager = application.getBean(SessionRunnerManager.class);
                    assertNotNull(manager);

                    Gauge activeGauge = registry.find("agent.sessions.active").gauge();
                    assertNotNull(activeGauge, "agent.sessions.active gauge must be registered");
                    assertEquals(0.0, activeGauge.value());

                    FunctionCounter evictedCounter = registry.find("agent.sessions.evicted").functionCounter();
                    assertNotNull(evictedCounter, "agent.sessions.evicted counter must be registered");
                    assertEquals(0.0, evictedCounter.count());

                    // Create session
                    manager.getOrCreate("user-session-1");
                    assertEquals(1.0, activeGauge.value());

                    manager.getOrCreate("user-session-2");
                    assertEquals(2.0, activeGauge.value());

                    manager.remove("user-session-1");
                    assertEquals(1.0, activeGauge.value());
                });
    }

    @Test
    void customSessionPropertiesAreBoundCorrectly() {
        context.withPropertyValues(
                "agent.session.max-sessions=250",
                "agent.session.idle-timeout=45m"
        ).run(application -> {
            AgentProperties properties = application.getBean(AgentProperties.class);
            assertNotNull(properties.session());
            assertEquals(250, properties.session().maxSessions());
            assertEquals(java.time.Duration.ofMinutes(45), properties.session().idleTimeout());
        });
    }
}
