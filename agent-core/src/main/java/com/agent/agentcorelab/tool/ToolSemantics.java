package com.agent.agentcorelab.tool;

import java.util.Objects;

/**
 * Standard governance and security semantics for an Agent tool.
 * <p>
 * Decoupled from application-level completion checks, {@code ToolSemantics} serves exclusively
 * as the foundation for security policies (e.g. {@code ToolPolicy}), human-in-the-loop gating,
 * and external MCP server governance.
 * <p>
 * Each semantic attribute uses a three-state {@link SemanticHint} ({@code YES}, {@code NO}, {@code UNKNOWN})
 * to prevent blind trust in external or unverified claims.
 *
 * @param readOnly    Whether the tool strictly inspects/queries state without mutating internal or external systems.
 * @param destructive Whether the tool performs irreversible, permanent, or high-consequence mutations.
 * @param idempotent  Whether invoking the tool multiple times with identical arguments yields the same side effects.
 * @param openWorld   Whether the tool interacts with unconstrained, dynamic external environments (e.g. public internet).
 */
public record ToolSemantics(
        SemanticHint readOnly,
        SemanticHint destructive,
        SemanticHint idempotent,
        SemanticHint openWorld
) {

    public ToolSemantics {
        readOnly = readOnly != null ? readOnly : SemanticHint.UNKNOWN;
        destructive = destructive != null ? destructive : SemanticHint.UNKNOWN;
        idempotent = idempotent != null ? idempotent : SemanticHint.UNKNOWN;
        openWorld = openWorld != null ? openWorld : SemanticHint.UNKNOWN;
    }

    /**
     * Predefined safe read-only tool semantics: read-only, non-destructive, idempotent, closed-world.
     */
    public static ToolSemantics readOnlySafe() {
        return new ToolSemantics(SemanticHint.YES, SemanticHint.NO, SemanticHint.YES, SemanticHint.NO);
    }

    /**
     * Predefined unknown semantics for uninspected or untrusted tools.
     */
    public static ToolSemantics unknown() {
        return new ToolSemantics(SemanticHint.UNKNOWN, SemanticHint.UNKNOWN, SemanticHint.UNKNOWN, SemanticHint.UNKNOWN);
    }

    /**
     * Predefined destructive action semantics (e.g. refund, delete, cancel).
     */
    public static ToolSemantics destructiveAction() {
        return new ToolSemantics(SemanticHint.NO, SemanticHint.YES, SemanticHint.NO, SemanticHint.UNKNOWN);
    }

    /**
     * Predefined non-destructive but state-mutating idempotent action semantics.
     */
    public static ToolSemantics idempotentAction() {
        return new ToolSemantics(SemanticHint.NO, SemanticHint.NO, SemanticHint.YES, SemanticHint.UNKNOWN);
    }

    /**
     * Derives default semantics based on legacy {@link RiskLevel}.
     *
     * @param riskLevel risk level
     * @return corresponding default semantics
     */
    public static ToolSemantics defaultFor(RiskLevel riskLevel) {
        if (riskLevel == RiskLevel.READ_ONLY) {
            return readOnlySafe();
        }
        if (riskLevel == RiskLevel.HIGH_RISK) {
            return new ToolSemantics(SemanticHint.NO, SemanticHint.YES, SemanticHint.UNKNOWN, SemanticHint.UNKNOWN);
        }
        return unknown();
    }

    /**
     * Returns true if this tool is guaranteed to be purely read-only and non-destructive.
     */
    public boolean isGuaranteedReadOnly() {
        return readOnly == SemanticHint.YES && destructive != SemanticHint.YES;
    }

    /**
     * Returns true if this tool might cause destructive mutations (either verified or unverified mutation).
     */
    public boolean isPotentiallyDestructive() {
        return destructive == SemanticHint.YES || (readOnly != SemanticHint.YES && destructive != SemanticHint.NO);
    }

    /**
     * Returns true if this tool is explicitly verified as idempotent.
     */
    public boolean isIdempotent() {
        return idempotent == SemanticHint.YES;
    }

    /**
     * Returns true if this tool interacts with an open-world external system.
     */
    public boolean isOpenWorld() {
        return openWorld == SemanticHint.YES;
    }

    /**
     * Merges host override semantics into this instance.
     * <p>
     * Any attribute in {@code hostOverride} that is not {@link SemanticHint#UNKNOWN} takes precedence.
     *
     * @param hostOverride host override semantics; null is safely ignored
     * @return merged semantics
     */
    public ToolSemantics overrideWith(ToolSemantics hostOverride) {
        if (hostOverride == null) {
            return this;
        }
        return new ToolSemantics(
                hostOverride.readOnly() != SemanticHint.UNKNOWN ? hostOverride.readOnly() : this.readOnly,
                hostOverride.destructive() != SemanticHint.UNKNOWN ? hostOverride.destructive() : this.destructive,
                hostOverride.idempotent() != SemanticHint.UNKNOWN ? hostOverride.idempotent() : this.idempotent,
                hostOverride.openWorld() != SemanticHint.UNKNOWN ? hostOverride.openWorld() : this.openWorld
        );
    }

    public ToolSemantics withReadOnly(SemanticHint hint) {
        return new ToolSemantics(hint, this.destructive, this.idempotent, this.openWorld);
    }

    public ToolSemantics withDestructive(SemanticHint hint) {
        return new ToolSemantics(this.readOnly, hint, this.idempotent, this.openWorld);
    }

    public ToolSemantics withIdempotent(SemanticHint hint) {
        return new ToolSemantics(this.readOnly, this.destructive, hint, this.openWorld);
    }

    public ToolSemantics withOpenWorld(SemanticHint hint) {
        return new ToolSemantics(this.readOnly, this.destructive, this.idempotent, hint);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private SemanticHint readOnly = SemanticHint.UNKNOWN;
        private SemanticHint destructive = SemanticHint.UNKNOWN;
        private SemanticHint idempotent = SemanticHint.UNKNOWN;
        private SemanticHint openWorld = SemanticHint.UNKNOWN;

        public Builder readOnly(SemanticHint readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder destructive(SemanticHint destructive) {
            this.destructive = destructive;
            return this;
        }

        public Builder idempotent(SemanticHint idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        public Builder openWorld(SemanticHint openWorld) {
            this.openWorld = openWorld;
            return this;
        }

        public ToolSemantics build() {
            return new ToolSemantics(readOnly, destructive, idempotent, openWorld);
        }
    }
}
