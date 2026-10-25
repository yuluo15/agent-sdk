package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.context.TrustedContext;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public java.util.Optional<Tool> find(String name) {
        return java.util.Optional.ofNullable(tools.get(name));
    }

    public ToolCatalog select(Collection<String> allowedNames) {
        return new ToolCatalog(allowedNames.stream().sorted().map(this::require).toList());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    /**
     * Returns a new {@link ToolCatalog} with governance semantics overridden for specified tools.
     * Tools not present in the overrides map remain unchanged.
     *
     * @param overrides map of tool names to host override semantics
     * @return new ToolCatalog with overrides applied
     */
    public ToolCatalog withSemanticsOverrides(Map<String, ToolSemantics> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        List<Tool> transformed = this.tools.values().stream().map(tool -> {
            ToolSemantics override = overrides.get(tool.definition().name());
            if (override != null) {
                ToolDefinition newDef = tool.definition().withOverriddenSemantics(override);
                return new OverriddenTool(tool, newDef);
            }
            return tool;
        }).toList();
        return new ToolCatalog(transformed);
    }

    /**
     * Converts this {@link ToolCatalog} into a mutable {@link ToolCandidatePool}.
     */
    public ToolCandidatePool toCandidatePool() {
        return ToolCandidatePool.of(tools.values());
    }

    /**
     * Assembles a scoped {@link ToolCatalog} for an {@link AgentSpec} from a {@link ToolCandidatePool}.
     */
    public static ToolCatalog from(ToolCandidatePool pool, AgentSpec spec) {
        Objects.requireNonNull(pool, "pool cannot be null");
        return pool.assembleCatalog(spec);
    }

    /**
     * Assembles a scoped {@link ToolCatalog} for an {@link AgentSpec} with tenant/role validation.
     */
    public static ToolCatalog from(ToolCandidatePool pool, AgentSpec spec, TrustedContext context) {
        Objects.requireNonNull(pool, "pool cannot be null");
        return pool.assembleCatalog(spec, context);
    }

    /**
     * Assembles a scoped {@link ToolCatalog} for explicit tool names from a {@link ToolCandidatePool}.
     */
    public static ToolCatalog from(ToolCandidatePool pool, Collection<String> toolNames) {
        Objects.requireNonNull(pool, "pool cannot be null");
        return pool.assembleCatalog(toolNames);
    }
}
