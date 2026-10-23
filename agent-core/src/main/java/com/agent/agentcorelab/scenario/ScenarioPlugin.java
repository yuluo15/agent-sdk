package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.tool.Tool;

import java.util.List;

public interface ScenarioPlugin {

    String id();

    String description();

    String instructions();

    List<Tool> tools();

    CompletionCheck completionCheck();

    /**
     * Declares the execution policy for this scenario (defaults to strict task execution).
     */
    default ExecutionPolicy policy() {
        return ExecutionPolicy.strict();
    }

    /**
     * Creates a new fluent builder for defining a {@link ScenarioPlugin}.
     */
    static ScenarioPluginBuilder builder(String id) {
        return new ScenarioPluginBuilder(id);
    }
}
