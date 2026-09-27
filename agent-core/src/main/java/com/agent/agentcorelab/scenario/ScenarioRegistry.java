package com.agent.agentcorelab.scenario;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.RunLimits;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolExecutor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ScenarioRegistry {
    private final Map<String, Entry> scenarios;
    private final ToolCatalog tools;

    public ScenarioRegistry(Collection<ScenarioPlugin> plugins, RunLimits limits) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, Tool> registered = new LinkedHashMap<>();
        Tool clarification = new ClarificationTool();
        registered.put(ClarificationTool.NAME, clarification);
        for (ScenarioPlugin plugin : plugins) {
            var names = new LinkedHashSet<String>();
            names.add(ClarificationTool.NAME);
            for (Tool tool : plugin.tools()) {
                String name = tool.definition().name();
                Tool previous = registered.putIfAbsent(name, tool);
                if (previous != null && previous != tool) {
                    throw new IllegalArgumentException("Duplicate or reserved tool name: " + name);
                }
                if (!names.add(name)) {
                    throw new IllegalArgumentException("Duplicate tool in scenario: " + name);
                }
            }
            AgentSpec spec = new AgentSpec(plugin.id(), plugin.instructions(), names, limits, plugin.completionCheck(), plugin.policy());
            if (plugin.description() == null || plugin.description().isBlank()
                    || entries.putIfAbsent(spec.id(), new Entry(plugin.description(), spec)) != null) {
                throw new IllegalArgumentException("Invalid or duplicate scenario: " + spec.id());
            }
        }
        this.scenarios = Map.copyOf(entries);
        this.tools = new ToolCatalog(registered.values());
        new ToolExecutor(tools, limits.maxToolResultBytes());
    }

    public Entry require(String id) {
        Entry entry = scenarios.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown scenario: " + id);
        }
        return entry;
    }

    public List<Entry> entries() {
        return scenarios.values().stream().sorted(java.util.Comparator.comparing(entry -> entry.spec().id())).toList();
    }

    public ToolCatalog tools() {
        return tools;
    }

    public record Entry(String description, AgentSpec spec) {
    }
}
