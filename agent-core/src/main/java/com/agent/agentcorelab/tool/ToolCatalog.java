package com.agent.agentcorelab.tool;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolCatalog {

    private final Map<String, Tool> tools;

    public ToolCatalog(Collection<Tool> tools) {
        Map<String, Tool> registered = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.definition().name();
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("Duplicate tool: " + name);
            }
        }
        this.tools = Collections.unmodifiableMap(registered);
    }

    public Tool require(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return tool;
    }

    public ToolCatalog select(Collection<String> allowedNames) {
        return new ToolCatalog(allowedNames.stream().sorted().map(this::require).toList());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }
}
