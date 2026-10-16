package com.agent.agentcorelab.bootstrap.logging;

import com.agent.agentcorelab.bootstrap.AgentConfiguration;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for Agent SDK structured logging.
 * <p>
 * Automatically registers {@link AgentLoggingRunEventListener} when SLF4J is present on the classpath,
 * enabling formatted runtime lifecycle logs and attribution markers by default.
 */
@AutoConfiguration(before = AgentConfiguration.class)
@ConditionalOnClass(Logger.class)
@ConditionalOnProperty(prefix = "agent.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentLoggingProperties.class)
public class AgentLoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentLoggingRunEventListener.class)
    public AgentLoggingRunEventListener agentLoggingRunEventListener(AgentLoggingProperties properties) {
        return new AgentLoggingRunEventListener(properties);
    }
}
