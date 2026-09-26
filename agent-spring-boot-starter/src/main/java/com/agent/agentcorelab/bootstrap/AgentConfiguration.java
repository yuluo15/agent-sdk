package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEventSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    @Bean
    @ConditionalOnMissingBean(ScenarioRegistry.class)
    public ScenarioRegistry scenarioRegistry(ObjectProvider<ScenarioPlugin> plugins, AgentProperties properties) {
        return new ScenarioRegistry(plugins.orderedStream().toList(), properties.limits());
    }

    @Bean
    @ConditionalOnMissingBean(ToolCatalog.class)
    public ToolCatalog toolCatalog(ScenarioRegistry scenarios) {
        return scenarios.tools();
    }

    @Bean
    @ConditionalOnMissingBean(RunEventSink.class)
    public RunEventSink runEventSink(AgentProperties properties) {
        return new JsonlRunEventSink(properties.workspace().traceRoot());
    }

    @Bean
    @ConditionalOnBean(ModelGateway.class)
    @ConditionalOnMissingBean(AgentRunnerFactory.class)
    public AgentRunnerFactory agentRunnerFactory(ModelGateway model, ToolCatalog tools, RunEventSink events) {
        return () -> new DefaultAgentRunner(model, tools, events);
    }

    @Bean
    @ConditionalOnBean(AgentRunnerFactory.class)
    @ConditionalOnMissingBean(AgentRunner.class)
    public DefaultAgentRunner agentRunner(AgentRunnerFactory factory) {
        return factory.create();
    }
}
