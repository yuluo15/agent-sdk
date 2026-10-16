package com.agent.agentcorelab.bootstrap.metrics;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Agent SDK Micrometer / Prometheus metrics.
 * <p>
 * Automatically active when {@link MeterRegistry} is present in the Spring application context,
 * providing zero-configuration out-of-the-box observability for agent runs.
 */
@AutoConfiguration(before = AgentConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "agent.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentMetricsRunEventListener.class)
    public AgentMetricsRunEventListener agentMetricsRunEventListener(MeterRegistry registry) {
        return new AgentMetricsRunEventListener(registry);
    }
}
