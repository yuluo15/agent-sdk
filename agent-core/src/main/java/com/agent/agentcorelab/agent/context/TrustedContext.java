package com.agent.agentcorelab.agent.context;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable security and identity context injected strictly by the host enterprise application.
 * <p>
 * Contains verified enterprise identity, tenant boundaries, and security scopes.
 * This context is strictly read-only and cannot be tampered with or forged by LLMs, prompts,
 * or tool input arguments.
 *
 * @param tenantId      Unique enterprise tenant identifier for multi-tenant isolation.
 * @param userPrincipal Verified caller identity (e.g. employee username, employee ID).
 * @param agentIdentity Identity or role assigned to this Agent runtime within the enterprise.
 * @param scopes        Immutable set of permission scopes or privileges granted to this execution.
 * @param traceId       Distributed tracing correlation ID (e.g. OpenTelemetry traceId).
 * @param requestId     Unique host request or invocation identifier.
 */
public record TrustedContext(
        String tenantId,
        String userPrincipal,
        String agentIdentity,
        Set<String> scopes,
        String traceId,
        String requestId
) {

    public static final String DEFAULT_TENANT = "default";
    public static final String ANONYMOUS_USER = "anonymous";
    public static final String DEFAULT_AGENT = "agent";

    public TrustedContext {
        tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : DEFAULT_TENANT;
        userPrincipal = (userPrincipal != null && !userPrincipal.isBlank()) ? userPrincipal.trim() : ANONYMOUS_USER;
        agentIdentity = (agentIdentity != null && !agentIdentity.isBlank()) ? agentIdentity.trim() : DEFAULT_AGENT;
        scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
        traceId = (traceId != null && !traceId.isBlank()) ? traceId.trim() : UUID.randomUUID().toString();
        requestId = (requestId != null && !requestId.isBlank()) ? requestId.trim() : UUID.randomUUID().toString();
    }

    public TrustedContext(String tenantId, String userPrincipal, String agentIdentity, Set<String> scopes) {
        this(tenantId, userPrincipal, agentIdentity, scopes, null, null);
    }

    /**
     * Default anonymous context for tests or unauthenticated runs.
     */
    public static TrustedContext anonymous() {
        return new TrustedContext(DEFAULT_TENANT, ANONYMOUS_USER, DEFAULT_AGENT, Set.of(), null, null);
    }

    /**
     * System-level context granting full administrative privileges.
     */
    public static TrustedContext system() {
        return new TrustedContext(DEFAULT_TENANT, "system", "system-agent", Set.of("*"), null, null);
    }

    /**
     * Checks if this context possesses the specified permission scope.
     * Wildcard {@code "*"} grants all scopes.
     *
     * @param scope required permission scope
     * @return true if granted
     */
    public boolean hasScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        return scopes.contains("*") || scopes.contains(scope);
    }

    /**
     * Checks if this context possesses at least one of the specified scopes.
     *
     * @param requiredScopes candidate scopes
     * @return true if at least one is granted
     */
    public boolean hasAnyScope(String... requiredScopes) {
        if (requiredScopes == null || requiredScopes.length == 0) {
            return true;
        }
        if (scopes.contains("*")) {
            return true;
        }
        return Arrays.stream(requiredScopes).anyMatch(this::hasScope);
    }

    /**
     * Checks if this context possesses all of the specified scopes.
     *
     * @param requiredScopes candidate scopes
     * @return true if all are granted
     */
    public boolean hasAllScopes(String... requiredScopes) {
        if (requiredScopes == null || requiredScopes.length == 0) {
            return true;
        }
        if (scopes.contains("*")) {
            return true;
        }
        return Arrays.stream(requiredScopes).allMatch(this::hasScope);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String tenantId = DEFAULT_TENANT;
        private String userPrincipal = ANONYMOUS_USER;
        private String agentIdentity = DEFAULT_AGENT;
        private Set<String> scopes = Set.of();
        private String traceId;
        private String requestId;

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userPrincipal(String userPrincipal) {
            this.userPrincipal = userPrincipal;
            return this;
        }

        public Builder agentIdentity(String agentIdentity) {
            this.agentIdentity = agentIdentity;
            return this;
        }

        public Builder scopes(Set<String> scopes) {
            this.scopes = scopes != null ? Set.copyOf(scopes) : Set.of();
            return this;
        }

        public Builder scopes(String... scopes) {
            this.scopes = (scopes != null && scopes.length > 0) ? Set.of(scopes) : Set.of();
            return this;
        }

        public Builder scope(String scope) {
            if (scope != null && !scope.isBlank()) {
                var newScopes = new java.util.HashSet<>(this.scopes);
                newScopes.add(scope.trim());
                this.scopes = java.util.Collections.unmodifiableSet(newScopes);
            }
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public TrustedContext build() {
            return new TrustedContext(tenantId, userPrincipal, agentIdentity, scopes, traceId, requestId);
        }
    }
}
