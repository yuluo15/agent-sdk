package com.agent.agentcorelab.bootstrap.logging;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import com.agent.agentcorelab.trace.CompositeRunEventSink;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEventSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoggingAutoConfigurationTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentLoggingAutoConfiguration.class, AgentConfiguration.class));

    @Test
    void autoConfiguresLoggingByDefault() {
        context.run(application -> {
            assertNull(application.getStartupFailure());
            assertNotNull(application.getBean(AgentLoggingRunEventListener.class));
            RunEventSink sink = application.getBean(RunEventSink.class);
            assertNotNull(sink);
            assertInstanceOf(CompositeRunEventSink.class, sink);
            CompositeRunEventSink composite = (CompositeRunEventSink) sink;
            assertTrue(composite.listeners().stream().anyMatch(l -> l instanceof AgentLoggingRunEventListener));
        });
    }

    @Test
    void backOffWhenExplicitlyDisabled() {
        context.withPropertyValues("agent.logging.enabled=false")
                .run(application -> {
                    assertNull(application.getStartupFailure());
                    assertTrue(application.getBeansOfType(AgentLoggingRunEventListener.class).isEmpty());
                    RunEventSink sink = application.getBean(RunEventSink.class);
                    assertNotNull(sink);
                    assertInstanceOf(JsonlRunEventSink.class, sink);
                });
    }
}
