package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.cli.AgentCli;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.cli", name = "enabled", havingValue = "true")
public class CliConfiguration {
    @Bean
    public ApplicationRunner agentCliApplicationRunner(
            AgentProperties properties, ScenarioRegistry scenarios, ObjectProvider<AgentRunner> runners,
            @Value("${agent.cli.scenario:file-inspection}") String scenario,
            @Value("${agent.model.enabled:false}") boolean modelEnabled) {
        return arguments -> {
            AgentRunner runner = null;
            if (modelEnabled) {
                try {
                    runner = runners.getIfAvailable();
                } catch (RuntimeException ignored) {
                    // Bean creation errors may embed credentials. CLI reports missing configuration instead.
                }
            }
            try (AgentCli cli = new AgentCli(scenarios, properties, runner, scenario,
                    new InputStreamReader(System.in, StandardCharsets.UTF_8),
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
                cli.run();
            }
        };
    }
}
