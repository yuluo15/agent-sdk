package com.agent.agentcorelab.tool;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable candidate tool descriptor with enterprise discovery and minimum-privilege metadata.
 * <p>
 * Decouples global tool discovery from agent-specific runtime assembly, preventing
 * unintended tool visibility or cross-agent privilege leakage.
 *
 * @param tool           The underlying executable tool instance.
 * @param domain         Logical domain/subsystem classification (e.g., "billing", "ops", "mcp").
 * @param tags           Categorization tags for capability discovery (e.g., "readonly", "refund").
 * @param requiredRole   Minimum caller role or scope required to assemble/invoke this tool (empty if unconstrained).
 * @param allowedTenants Explicit tenant whitelist permitted to assemble this tool (empty if global/all tenants).
 * @param enabled        Whether this candidate tool is currently active in the pool.
 */
public record CandidateTool(
        Tool tool,
        String domain,
        Set<String> tags,
        String requiredRole,
        Set<String> allowedTenants,
        boolean enabled
) {

    public CandidateTool {
        Objects.requireNonNull(tool, "tool cannot be null");
        domain = domain != null && !domain.isBlank() ? domain.trim() : "default";
        tags = tags != null ? Set.copyOf(tags) : Set.of();
        requiredRole = requiredRole != null && !requiredRole.isBlank() ? requiredRole.trim() : "";
        allowedTenants = allowedTenants != null ? Set.copyOf(allowedTenants) : Set.of();
    }

    public String name() {
        return tool.definition().name();
    }

    public ToolDefinition definition() {
        return tool.definition();
    }

    /**
     * Checks whether this tool is permitted for the given tenant.
     * Global tools (with empty {@code allowedTenants}) are accessible to all tenants.
     */
    public boolean isAllowedForTenant(String tenantId) {
        if (allowedTenants.isEmpty()) {
            return true;
        }
        return tenantId != null && !tenantId.isBlank() && allowedTenants.contains(tenantId);
    }

    /**
     * Checks whether this tool is permitted for the given role or scope.
     */
    public boolean isAllowedForRole(String role) {
        if (requiredRole.isEmpty()) {
            return true;
        }
        return role != null && requiredRole.equalsIgnoreCase(role.trim());
    }

    /**
     * Checks whether this tool's required role is satisfied by any of the granted scopes.
     */
    public boolean isAllowedForScopes(Set<String> scopes) {
        if (requiredRole.isEmpty()) {
            return true;
        }
        if (scopes == null || scopes.isEmpty()) {
            return false;
        }
        if (scopes.contains("*") || scopes.contains("all")) {
            return true;
        }
        return scopes.stream().anyMatch(s -> s.equalsIgnoreCase(requiredRole));
    }

    /**
     * Checks whether this tool contains the given tag.
     */
    public boolean matchesTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        return tags.contains(tag.trim());
    }

    /**
     * Returns a copy of this candidate tool with the specified enabled status.
     */
    public CandidateTool withEnabled(boolean newEnabled) {
        return new CandidateTool(tool, domain, tags, requiredRole, allowedTenants, newEnabled);
    }

    // =========================================================================
    // Static Factories
    // =========================================================================

    public static CandidateTool of(Tool tool) {
        return new CandidateTool(tool, "default", Set.of(), "", Set.of(), true);
    }

    public static CandidateTool of(Tool tool, String domain) {
        return new CandidateTool(tool, domain, Set.of(), "", Set.of(), true);
    }

    public static CandidateTool of(Tool tool, String domain, Set<String> tags) {
        return new CandidateTool(tool, domain, tags, "", Set.of(), true);
    }

    public static CandidateTool of(Tool tool, String domain, Set<String> tags, String requiredRole) {
        return new CandidateTool(tool, domain, tags, requiredRole, Set.of(), true);
    }

    public static CandidateTool of(Tool tool, String domain, Set<String> tags, String requiredRole, Set<String> allowedTenants) {
        return new CandidateTool(tool, domain, tags, requiredRole, allowedTenants, true);
    }
}
