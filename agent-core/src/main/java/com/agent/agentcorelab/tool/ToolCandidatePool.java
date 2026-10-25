package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.context.TrustedContext;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Enterprise tool discovery registry and least-privilege assembly SPI.
 * <p>
 * Decouples the global candidate tool pool (from Spring beans, MCP servers, and skills)
 * from individual Agent execution runtimes. Ensures that each {@link AgentSpec} only receives
 * a tightly scoped {@link ToolCatalog} containing authorized tools.
 */
public interface ToolCandidatePool {

    /**
     * Registers a candidate tool descriptor into the pool.
     */
    void register(CandidateTool candidate);

    /**
     * Registers a plain tool instance using default metadata.
     */
    default void register(Tool tool) {
        register(CandidateTool.of(tool));
    }

    /**
     * Registers a collection of tools with specified domain and tags.
     */
    default void registerAll(Collection<Tool> tools, String domain, Set<String> tags) {
        if (tools != null) {
            for (Tool tool : tools) {
                register(CandidateTool.of(tool, domain, tags));
            }
        }
    }

    /**
     * Unregisters a tool from the pool by its name.
     *
     * @param toolName unique tool name
     * @return {@code true} if the tool was present and removed
     */
    boolean unregister(String toolName);

    /**
     * Finds a registered candidate tool by its unique name.
     */
    Optional<CandidateTool> find(String toolName);

    /**
     * Checks if a candidate tool exists in the pool.
     */
    default boolean contains(String toolName) {
        return find(toolName).isPresent();
    }

    /**
     * Returns an unmodifiable snapshot list of all registered candidate tools.
     */
    List<CandidateTool> allCandidates();

    /**
     * Returns the total count of registered candidate tools.
     */
    default int size() {
        return allCandidates().size();
    }

    /**
     * Filters candidate tools matching the given predicate.
     */
    List<CandidateTool> filter(Predicate<CandidateTool> predicate);

    /**
     * Filters candidate tools belonging to the specified domain.
     */
    default List<CandidateTool> filterByDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return List.of();
        }
        return filter(c -> domain.equalsIgnoreCase(c.domain()));
    }

    /**
     * Filters candidate tools matching any of the specified tags.
     */
    default List<CandidateTool> filterByTags(String... tags) {
        if (tags == null || tags.length == 0) {
            return List.of();
        }
        Set<String> tagSet = Set.of(tags);
        return filter(c -> c.tags().stream().anyMatch(tagSet::contains));
    }

    /**
     * Assembles a strictly minimal-privilege {@link ToolCatalog} for an {@link AgentSpec}.
     * Only tools explicitly declared in {@link AgentSpec#toolNames()} are assembled.
     *
     * @param spec the agent specification
     * @return scoped ToolCatalog containing strictly the authorized tools
     * @throws ToolAssemblyException if any requested tool is missing or disabled
     */
    ToolCatalog assembleCatalog(AgentSpec spec);

    /**
     * Assembles a strictly minimal-privilege {@link ToolCatalog} for an {@link AgentSpec}
     * with tenant isolation and role/scope authorization checks.
     *
     * @param spec    the agent specification
     * @param context the caller's trusted security context (tenant, identity, scopes)
     * @return scoped ToolCatalog containing strictly the authorized tools
     * @throws ToolAssemblyException if any requested tool violates tenant or role boundaries
     */
    ToolCatalog assembleCatalog(AgentSpec spec, TrustedContext context);

    /**
     * Assembles a {@link ToolCatalog} for explicit tool names.
     *
     * @param toolNames required tool names
     * @return scoped ToolCatalog
     * @throws ToolAssemblyException if any tool is missing or disabled
     */
    ToolCatalog assembleCatalog(Collection<String> toolNames);

    /**
     * Assembles a {@link ToolCatalog} for tools belonging to the specified domain.
     */
    default ToolCatalog assembleCatalogByDomain(String domain) {
        List<CandidateTool> candidates = filterByDomain(domain);
        return new ToolCatalog(candidates.stream().filter(CandidateTool::enabled).map(CandidateTool::tool).toList());
    }

    /**
     * Assembles a {@link ToolCatalog} for tools matching any of the specified tags.
     */
    default ToolCatalog assembleCatalogByTags(String... tags) {
        List<CandidateTool> candidates = filterByTags(tags);
        return new ToolCatalog(candidates.stream().filter(CandidateTool::enabled).map(CandidateTool::tool).toList());
    }

    /**
     * Converts the entire active candidate pool into a {@link ToolCatalog}.
     */
    default ToolCatalog toCatalog() {
        return new ToolCatalog(allCandidates().stream().filter(CandidateTool::enabled).map(CandidateTool::tool).toList());
    }

    /**
     * Creates a new, thread-safe in-memory candidate tool pool.
     */
    static ToolCandidatePool create() {
        return new DefaultToolCandidatePool();
    }

    /**
     * Creates an in-memory candidate tool pool pre-populated with tools.
     */
    static ToolCandidatePool of(Collection<Tool> tools) {
        DefaultToolCandidatePool pool = new DefaultToolCandidatePool();
        if (tools != null) {
            tools.forEach(pool::register);
        }
        return pool;
    }
}
