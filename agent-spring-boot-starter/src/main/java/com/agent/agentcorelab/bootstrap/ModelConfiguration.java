package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.SpringAiModelGateway;
import com.agent.agentcorelab.model.TokenEstimator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = AgentConfiguration.class)
@EnableConfigurationProperties({ModelProperties.class, AgentProperties.class})
public class ModelConfiguration {
    @Bean
    @ConditionalOnMissingBean(TokenEstimator.class)
    public TokenEstimator tokenEstimator() {
        return TokenEstimator.conservativeUtf8();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.model", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ModelGateway.class)
    public ModelGateway modelGateway(ModelProperties properties, AgentProperties agentProperties, TokenEstimator estimator) {
        if (properties.getMaxOutputTokens() > agentProperties.limits().outputTokenReserve()) {
            throw new IllegalArgumentException("agent.model.maxOutputTokens must not exceed agent.limits.outputTokenReserve");
        }
        return new SpringAiModelGateway(properties.getApiKey(), properties.getBaseUrl(),
                properties.getModel(), properties.getMaxOutputTokens(), estimator);
    }
}
