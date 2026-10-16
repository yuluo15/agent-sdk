package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.trace.CompositeRunEventSink;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEventListener;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.memory.ConversationMemory;
import com.agent.agentcorelab.memory.InMemoryConversationMemory;
import java.util.List;
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
    public RunEventSink runEventSink(AgentProperties properties, ObjectProvider<RunEventListener> listenersProvider) {
        JsonlRunEventSink jsonl = new JsonlRunEventSink(properties.workspace().traceRoot());
        List<RunEventListener> listeners = listenersProvider.orderedStream().toList();
        if (listeners.isEmpty()) {
            return jsonl;
        }
        return new CompositeRunEventSink(jsonl, listeners);
    }

    @Bean
    @ConditionalOnMissingBean(com.agent.agentcorelab.memory.ConversationCompactor.class)
    public com.agent.agentcorelab.memory.ConversationCompactor conversationCompactor() {
        return new com.agent.agentcorelab.memory.RuleBasedConversationCompactor();
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMemory.class)
    public ConversationMemory conversationMemory(ObjectProvider<com.agent.agentcorelab.memory.ConversationCompactor> compactorProvider) {
        com.agent.agentcorelab.memory.ConversationCompactor compactor = compactorProvider.getIfAvailable(
                com.agent.agentcorelab.memory.RuleBasedConversationCompactor::new);
        return new InMemoryConversationMemory(
                InMemoryConversationMemory.DEFAULT_MAX_CONVERSATIONS,
                InMemoryConversationMemory.DEFAULT_MAX_MESSAGES_PER_CONVERSATION,
                compactor
        );
    }

    @Bean
    @ConditionalOnMissingBean(com.agent.agentcorelab.memory.UserProfileMemory.class)
    public com.agent.agentcorelab.memory.UserProfileMemory userProfileMemory() {
        return new com.agent.agentcorelab.memory.InMemoryUserProfileMemory();
    }

    @Bean
    @ConditionalOnBean(ModelGateway.class)
    @ConditionalOnMissingBean(AgentRunnerFactory.class)
    public AgentRunnerFactory agentRunnerFactory(ModelGateway model, ToolCatalog tools, RunEventSink events,
                                                 ObjectProvider<RunEventListener> listenersProvider,
                                                 ObjectProvider<ConversationMemory> memoryProvider,
                                                 ObjectProvider<com.agent.agentcorelab.memory.UserProfileMemory> userProfileProvider) {
        ConversationMemory memory = memoryProvider.getIfAvailable(InMemoryConversationMemory::new);
        com.agent.agentcorelab.memory.UserProfileMemory profiles = userProfileProvider.getIfAvailable(
                com.agent.agentcorelab.memory.InMemoryUserProfileMemory::new);
        List<RunEventListener> listeners = listenersProvider.orderedStream().toList();
        RunEventSink effectiveEvents = events;
        if (!listeners.isEmpty() && !(events instanceof CompositeRunEventSink)) {
            effectiveEvents = new CompositeRunEventSink(events, listeners);
        }
        RunEventSink finalEvents = effectiveEvents;
        return () -> new DefaultAgentRunner(model, tools, finalEvents, memory, profiles);
    }

    @Bean
    @ConditionalOnBean(AgentRunnerFactory.class)
    @ConditionalOnMissingBean(AgentRunner.class)
    public DefaultAgentRunner agentRunner(AgentRunnerFactory factory) {
        return factory.create();
    }
}
