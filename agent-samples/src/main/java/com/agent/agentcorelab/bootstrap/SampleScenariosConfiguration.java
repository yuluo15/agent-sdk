package com.agent.agentcorelab.bootstrap;

import com.agent.agentcorelab.sample.analysis.DataAnalysisScenario;
import com.agent.agentcorelab.sample.files.FileInspectionScenario;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SampleScenariosConfiguration {
    @Bean
    public ScenarioPlugin dataAnalysisScenario() {
        return new DataAnalysisScenario();
    }

    @Bean
    public ScenarioPlugin fileInspectionScenario() {
        return new FileInspectionScenario();
    }
}
