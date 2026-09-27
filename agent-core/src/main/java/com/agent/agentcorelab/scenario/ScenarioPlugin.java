package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.ExecutionPolicy;
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
}
