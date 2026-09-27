package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.ExecutionPolicy;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.annotation.AnnotatedToolScanner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Fluent builder for creating {@link ScenarioPlugin} instances with minimal boilerplate.
 */
public final class ScenarioPluginBuilder {

    private final String id;
    private String description;
    private String instructions;
    private final List<Tool> tools = new ArrayList<>();
    private CompletionCheck completionCheck = (run, answer) -> CompletionCheck.Result.satisfied();
    private ExecutionPolicy policy = ExecutionPolicy.strict();

    ScenarioPluginBuilder(String id) {
        this.id = Objects.requireNonNull(id, "scenario id cannot be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("scenario id cannot be blank");
        }
    }

    public ScenarioPluginBuilder description(String description) {
        this.description = description;
        return this;
    }

    public ScenarioPluginBuilder instructions(String instructions) {
        this.instructions = instructions;
        return this;
    }

    public ScenarioPluginBuilder addTool(Tool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    public ScenarioPluginBuilder addTools(Collection<Tool> tools) {
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    public ScenarioPluginBuilder addAnnotatedTools(Object target) {
        if (target != null) {
            this.tools.addAll(AnnotatedToolScanner.scan(target));
        }
        return this;
    }

    public ScenarioPluginBuilder addAnnotatedTools(Object... targets) {
        if (targets != null) {
            this.tools.addAll(AnnotatedToolScanner.scanAll(targets));
        }
        return this;
    }

    public ScenarioPluginBuilder completionCheck(CompletionCheck completionCheck) {
        this.completionCheck = Objects.requireNonNull(completionCheck, "completionCheck cannot be null");
        return this;
    }

    public ScenarioPluginBuilder policy(ExecutionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }

    public ScenarioPlugin build() {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Scenario description is required: " + id);
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("Scenario instructions are required: " + id);
        }

        return new SimpleScenarioPlugin(id, description, instructions, List.copyOf(tools), completionCheck, policy);
    }

    private record SimpleScenarioPlugin(
            String id,
            String description,
            String instructions,
            List<Tool> tools,
            CompletionCheck completionCheck,
            ExecutionPolicy policy
    ) implements ScenarioPlugin {}
}
