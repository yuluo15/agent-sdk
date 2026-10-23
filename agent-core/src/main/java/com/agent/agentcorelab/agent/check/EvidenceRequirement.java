package com.agent.agentcorelab.agent.check;

/**
 * Verification constraints for factual evidence and tool observations.
 */
public record EvidenceRequirement(
        boolean requireCitations,
        int minObservations,
        boolean mustCiteInAnswer
) {
    public EvidenceRequirement {
        if (minObservations < 0) {
            minObservations = 0;
        }
    }

    public static EvidenceRequirement none() {
        return new EvidenceRequirement(false, 0, false);
    }

    public static EvidenceRequirement observationsOnly(int minObservations) {
        return new EvidenceRequirement(false, minObservations, false);
    }

    public static EvidenceRequirement citationsRequired(int minObservations) {
        return new EvidenceRequirement(true, minObservations, true);
    }
}
