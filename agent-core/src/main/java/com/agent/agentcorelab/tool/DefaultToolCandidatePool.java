package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.tool.builtin.CheckDeliverableTool;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.tool.builtin.ReadWorkItemTool;
import com.agent.agentcorelab.tool.builtin.SaveWorkNoteTool;
import com.agent.agentcorelab.tool.builtin.UpdatePlanTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Standard thread-safe implementation of {@link ToolCandidatePool}.
 */
public final class DefaultToolCandidatePool implements ToolCandidatePool {

    private final Map<String, CandidateTool> candidates = new ConcurrentHashMap<>();

    public DefaultToolCandidatePool() {
        // Register standard framework utilities by default
        registerBuiltin(new ClarificationTool());
        registerBuiltin(new ReadWorkItemTool());
        registerBuiltin(new UpdatePlanTool());
        registerBuiltin(new SaveWorkNoteTool());
        registerBuiltin(new CheckDeliverableTool());
    }

    private void registerBuiltin(Tool tool) {
        CandidateTool candidate = CandidateTool.of(tool, "system", java.util.Set.of("builtin", "system"));
        candidates.put(tool.definition().name(), candidate);
    }

    @Override
    public void register(CandidateTool candidate) {
        Objects.requireNonNull(candidate, "candidate cannot be null");
        candidates.put(candidate.name(), candidate);
    }

    @Override
    public boolean unregister(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return candidates.remove(toolName.trim()) != null;
    }

    @Override
    public Optional<CandidateTool> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(candidates.get(toolName.trim()));
    }

    @Override
    public List<CandidateTool> allCandidates() {
        return List.copyOf(candidates.values());
    }

    @Override
    public List<CandidateTool> filter(Predicate<CandidateTool> predicate) {
        Objects.requireNonNull(predicate, "predicate cannot be null");
        return candidates.values().stream().filter(predicate).toList();
    }

    @Override
    public ToolCatalog assembleCatalog(AgentSpec spec) {
        return assembleCatalog(spec, null);
    }

    @Override
    public ToolCatalog assembleCatalog(AgentSpec spec, TrustedContext context) {
        Objects.requireNonNull(spec, "spec cannot be null");
        String agentId = spec.id();
        Map<String, Tool> assembled = new LinkedHashMap<>();

        for (String toolName : spec.toolNames()) {
            Tool tool = resolveAndAuthorize(agentId, toolName, context);
            assembled.put(tool.definition().name(), tool);
        }

        return new ToolCatalog(assembled.values());
    }

    @Override
    public ToolCatalog assembleCatalog(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return new ToolCatalog(Collections.emptyList());
        }
        Map<String, Tool> assembled = new LinkedHashMap<>();
        for (String toolName : toolNames) {
            Tool tool = resolveAndAuthorize(null, toolName, null);
            assembled.put(tool.definition().name(), tool);
        }
        return new ToolCatalog(assembled.values());
    }

    private Tool resolveAndAuthorize(String agentId, String toolName, TrustedContext context) {
        CandidateTool candidate = candidates.get(toolName);
        if (candidate == null) {
            throw new ToolAssemblyException(
                    agentId,
                    toolName,
                    "TOOL_NOT_FOUND",
                    "Tool '" + toolName + "' declared in agent '" + (agentId != null ? agentId : "anonymous")
                            + "' was not found in the Candidate Tool Pool"
            );
        }

        if (!candidate.enabled()) {
            throw new ToolAssemblyException(
                    agentId,
                    toolName,
                    "TOOL_DISABLED",
                    "Tool '" + toolName + "' is currently disabled in the Candidate Tool Pool"
            );
        }

        if (context != null) {
            // 1. Tenant boundary enforcement
            if (!candidate.isAllowedForTenant(context.tenantId())) {
                throw new ToolAssemblyException(
                        agentId,
                        toolName,
                        "TENANT_ISOLATION_VIOLATION",
                        "Tool '" + toolName + "' is restricted to tenants " + candidate.allowedTenants()
                                + " and cannot be assembled for tenant '" + context.tenantId() + "'"
                );
            }

            // 2. Role / Scope authorization enforcement
            if (!candidate.requiredRole().isEmpty()) {
                boolean roleMatches = candidate.isAllowedForRole(context.userPrincipal())
                        || candidate.isAllowedForScopes(context.scopes());
                if (!roleMatches) {
                    throw new ToolAssemblyException(
                            agentId,
                            toolName,
                            "ROLE_UNAUTHORIZED",
                            "Tool '" + toolName + "' requires role '" + candidate.requiredRole()
                                    + "', but caller context '" + context.userPrincipal() + "' lacks required scopes"
                    );
                }
            }
        }

        return candidate.tool();
    }
}
