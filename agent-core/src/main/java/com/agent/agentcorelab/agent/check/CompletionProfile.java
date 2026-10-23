package com.agent.agentcorelab.agent.check;

import java.util.Set;

/**
 * Categorical completion profiles determining which evaluators are activated in the completion pipeline.
 * <p>
 * Profiles are composable (e.g. {@code Set.of(TEXT, EVIDENCE, ARTIFACT)}).
 */
public enum CompletionProfile {
    /**
     * Requires a substantive, non-empty final answer text that directly addresses the user's objective.
     */
    TEXT,

    /**
     * Requires that factual conclusions in the answer or deliverables are backed by verifiable
     * tool observations (e.g. [E1] evidence citations).
     */
    EVIDENCE,

    /**
     * Requires that tangible logical deliverables (e.g. reports, data exports, configs)
     * are physically verified and non-empty in the workspace session.
     */
    ARTIFACT,

    /**
     * Requires that side-effecting operations (e.g. updates, payments, waivers) have verified
     * execution receipts and approved authorizations.
     */
    ACTION;

    public static Set<CompletionProfile> textOnly() {
        return Set.of(TEXT);
    }

    public static Set<CompletionProfile> textWithEvidence() {
        return Set.of(TEXT, EVIDENCE);
    }

    public static Set<CompletionProfile> fullReport() {
        return Set.of(TEXT, EVIDENCE, ARTIFACT);
    }

    public static Set<CompletionProfile> actionable() {
        return Set.of(TEXT, EVIDENCE, ACTION);
    }
}
