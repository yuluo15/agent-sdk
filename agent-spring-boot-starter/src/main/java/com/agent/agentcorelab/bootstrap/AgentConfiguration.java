package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.AgentRunnerFactory;
import com.agent.agentcorelab.agent.DefaultAgentRunner;
import com.agent.agentcorelab.agent.DefaultSessionRunnerManager;
import com.agent.agentcorelab.agent.SessionRunnerManager;
import com.agent.agentcorelab.checkpoint.CheckpointSpi;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.CandidateTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCandidatePool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolDefinition;
import java.util.Set;
import com.agent.agentcorelab.trace.AuditTrailSpi;
import com.agent.agentcorelab.trace.CompositeRunEventSink;
import com.agent.agentcorelab.trace.JsonlRunEventSink;
import com.agent.agentcorelab.trace.RunEventListener;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.memory.ConversationMemory;
import com.agent.agentcorelab.memory.InMemoryConversationMemory;
import com.agent.agentcorelab.memory.compaction.ConversationCompactor;
import com.agent.agentcorelab.memory.compaction.RuleBasedConversationCompactor;
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
    @ConditionalOnMissingBean(ToolCandidatePool.class)
    public ToolCandidatePool toolCandidatePool(ObjectProvider<Tool> singleToolsProvider,
                                               ObjectProvider<List<Tool>> toolListsProvider,
                                               ObjectProvider<CandidateTool> candidateToolsProvider,
                                               ObjectProvider<List<CandidateTool>> candidateListsProvider,
                                               ObjectProvider<ScenarioRegistry> scenarioRegistryProvider) {
        ToolCandidatePool pool = ToolCandidatePool.create();
        scenarioRegistryProvider.ifAvailable(scenarios -> {
            for (ToolDefinition def : scenarios.tools().definitions()) {
                pool.register(CandidateTool.of(scenarios.tools().require(def.name()), "scenario", Set.of("scenario")));
            }
        });
        candidateToolsProvider.orderedStream().forEach(pool::register);
        candidateListsProvider.orderedStream().forEach(list -> {
            if (list != null) list.forEach(pool::register);
        });
        singleToolsProvider.orderedStream().forEach(pool::register);
        toolListsProvider.orderedStream().forEach(list -> {
            if (list != null) list.forEach(pool::register);
        });
        return pool;
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
    @ConditionalOnMissingBean(ConversationCompactor.class)
    public ConversationCompactor conversationCompactor() {
        return new RuleBasedConversationCompactor();
    }

    @Bean
    @ConditionalOnMissingBean(ConversationMemory.class)
    public ConversationMemory conversationMemory(ObjectProvider<ConversationCompactor> compactorProvider) {
        ConversationCompactor compactor = compactorProvider.getIfAvailable(
                RuleBasedConversationCompactor::new);
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
    @ConditionalOnMissingBean(AuditTrailSpi.class)
    public AuditTrailSpi auditTrail() {
        return AuditTrailSpi.noop();
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointSpi.class)
    public CheckpointSpi checkpointStore() {
        return CheckpointSpi.noop();
    }

    @Bean
    @ConditionalOnBean(ModelGateway.class)
    @ConditionalOnMissingBean(AgentRunnerFactory.class)
    public AgentRunnerFactory agentRunnerFactory(ModelGateway model, ToolCatalog tools, RunEventSink events,
                                                 ObjectProvider<ToolCandidatePool> candidatePoolProvider,
                                                 ObjectProvider<RunEventListener> listenersProvider,
                                                 ObjectProvider<ConversationMemory> memoryProvider,
                                                 ObjectProvider<com.agent.agentcorelab.memory.UserProfileMemory> userProfileProvider,
                                                 ObjectProvider<AuditTrailSpi> auditTrailProvider,
                                                 ObjectProvider<CheckpointSpi> checkpointStoreProvider) {
        ConversationMemory memory = memoryProvider.getIfAvailable(InMemoryConversationMemory::new);
        com.agent.agentcorelab.memory.UserProfileMemory profiles = userProfileProvider.getIfAvailable(
                com.agent.agentcorelab.memory.InMemoryUserProfileMemory::new);
        AuditTrailSpi auditTrail = auditTrailProvider.getIfAvailable(AuditTrailSpi::noop);
        CheckpointSpi checkpointStore = checkpointStoreProvider.getIfAvailable(CheckpointSpi::noop);
        ToolCandidatePool candidatePool = candidatePoolProvider.getIfAvailable(tools::toCandidatePool);
        List<RunEventListener> listeners = listenersProvider.orderedStream().toList();
        RunEventSink effectiveEvents = events;
        if (!listeners.isEmpty() && !(events instanceof CompositeRunEventSink)) {
            effectiveEvents = new CompositeRunEventSink(events, listeners);
        }
        RunEventSink finalEvents = effectiveEvents;
        return () -> new DefaultAgentRunner(model, tools, candidatePool, finalEvents, memory, profiles, System::nanoTime,
                DefaultAgentRunner.DEFAULT_MAX_ACTIVE_RUNS, auditTrail, checkpointStore);
    }

    @Bean
    @ConditionalOnBean(AgentRunnerFactory.class)
    @ConditionalOnMissingBean(SessionRunnerManager.class)
    public SessionRunnerManager sessionRunnerManager(AgentRunnerFactory factory, AgentProperties properties,
                                                     ObjectProvider<MeterRegistry> meterRegistryProvider) {
        DefaultSessionRunnerManager manager = new DefaultSessionRunnerManager(
                factory, properties.session().maxSessions(), properties.session().idleTimeout());
        meterRegistryProvider.ifAvailable(registry -> {
            Gauge.builder("agent.sessions.active", manager, SessionRunnerManager::sessionCount)
                    .description("Number of currently active managed agent runner sessions")
                    .register(registry);
            FunctionCounter.builder("agent.sessions.evicted", manager, SessionRunnerManager::evictedCount)
                    .description("Total number of evicted agent runner sessions")
                    .register(registry);
        });
        return manager;
    }

    /**
     * @deprecated Since 1.1.0. Injecting a global singleton {@link AgentRunner} causes active task conflicts
     * in multi-user/multi-session environments. Use {@link SessionRunnerManager} or {@link AgentRunnerFactory} instead.
     */
    @Bean
    @Deprecated(since = "1.1.0", forRemoval = false)
    @ConditionalOnBean(AgentRunnerFactory.class)
    @ConditionalOnMissingBean(AgentRunner.class)
    public DefaultAgentRunner agentRunner(AgentRunnerFactory factory) {
        return factory.create();
    }
}
