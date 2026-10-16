package com.agent.agentcorelab.bootstrap.metrics;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import com.agent.agentcorelab.trace.CompositeRunEventSink;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEventSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class AgentMetricsAutoConfigurationTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentMetricsAutoConfiguration.class, AgentConfiguration.class));

    @Test
    void autoConfiguresMetricsWhenMeterRegistryPresent() {
        context.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertNotNull(application.getBean(AgentMetricsRunEventListener.class));
                    RunEventSink sink = application.getBean(RunEventSink.class);
                    assertNotNull(sink);
                    assertInstanceOf(CompositeRunEventSink.class, sink);
                    CompositeRunEventSink composite = (CompositeRunEventSink) sink;
                    assertInstanceOf(JsonlRunEventSink.class, composite.primary());
                    assertTrue(composite.listeners().stream().anyMatch(l -> l instanceof AgentMetricsRunEventListener));
                });
    }

    @Test
    void backOffWhenMeterRegistryAbsent() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            assertTrue(application.getBeansOfType(AgentMetricsRunEventListener.class).isEmpty());
            RunEventSink sink = application.getBean(RunEventSink.class);
            assertNotNull(sink);
            assertInstanceOf(JsonlRunEventSink.class, sink);
        });
    }

    @Test
    void backOffWhenDisabledExplicitly() {
        context.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("agent.metrics.enabled=false")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertTrue(application.getBeansOfType(AgentMetricsRunEventListener.class).isEmpty());
                    RunEventSink sink = application.getBean(RunEventSink.class);
                    assertNotNull(sink);
                    assertInstanceOf(JsonlRunEventSink.class, sink);
                });
    }
}
