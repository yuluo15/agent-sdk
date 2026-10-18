package com.agent.agentcorelab.eval.dataset;

import java.util.Collections;
import java.util.List;

/**
 * Evidence citation assertions and observation cross-checking rules.
 */
public record EvidenceAssertion(
        List<String> requiredEvidenceIds,
        boolean requireStructuredCitationBlock,
        boolean rejectFabricatedIds
) {
    public EvidenceAssertion {
        requiredEvidenceIds = requiredEvidenceIds == null ? Collections.emptyList() : List.copyOf(requiredEvidenceIds);
    }

    public static EvidenceAssertion empty() {
        return new EvidenceAssertion(Collections.emptyList(), false, false);
    }
}
