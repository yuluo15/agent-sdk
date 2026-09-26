package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.RunLimits;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioRegistryTests {
    private final RunLimits limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));

    @Test
    void eachScenarioGetsOnlyItsOwnToolsAndCompletionCheck() {
        Tool read = tool("read");
        Tool write = tool("write");
        ScenarioPlugin first = plugin("research", List.of(read));
        ScenarioPlugin second = plugin("analysis", List.of(read, write));
        ScenarioRegistry registry = new ScenarioRegistry(List.of(first, second), limits);
        assertEquals(Set.of("read", ClarificationTool.NAME), registry.require("research").spec().toolNames());
        assertEquals(Set.of("read", "write", ClarificationTool.NAME), registry.require("analysis").spec().toolNames());
        assertEquals(3, registry.tools().definitions().size());
        assertThrows(IllegalArgumentException.class, () -> registry.require("not-installed"));
    }

    @Test
    void duplicateScenarioAndDifferentImplementationsWithSameNameAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioRegistry(
                List.of(plugin("research", List.of()), plugin("research", List.of())), limits));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioRegistry(
                List.of(plugin("research", List.of(tool("read"))), plugin("analysis", List.of(tool("read")))), limits));
    }

    @Test
    void pluginsCannotReplaceRuntimeControlTools() {
        assertThrows(IllegalArgumentException.class, () -> new ScenarioRegistry(
                List.of(plugin("research", List.of(new ClarificationTool()))), limits));
    }

    private ScenarioPlugin plugin(String id, List<Tool> tools) {
        return new ScenarioPlugin() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "Test scenario";
            }

            @Override
            public String instructions() {
                return "Use the selected scenario's tools";
            }

            @Override
            public List<Tool> tools() {
                return tools;
            }

            @Override
            public CompletionCheck completionCheck() {
                return (run, answer) -> new CompletionCheck.Result(true, "");
            }
        };
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "Test tool", "{\"type\":\"object\",\"additionalProperties\":false}");
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"ok\":true}");
            }
        };
    }
}
