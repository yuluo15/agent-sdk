package com.agent.agentcorelab.eval.assertion;

import com.agent.agentcorelab.eval.dataset.EvidenceAssertion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asserts evidence citations, structured reference format, and observation cross-checking.
 */
public final class EvidenceAssertor {

    private static final Pattern CITATION_BLOCK_PATTERN = Pattern.compile(
            "(?:【参考依据】|\\[参考依据\\]|【References】|\\[References\\])(.*?)(?:\\n\\n|$)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EVIDENCE_ID_PATTERN = Pattern.compile(
            "\\[?([a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+)\\]?"
    );

    private EvidenceAssertor() {
    }

    /**
     * Evaluates evidence citation and cross-validates against verified observations.
     *
     * @param assertion evidence assertion configuration
     * @param finalOutput agent final response text
     * @param verifiedObservationPayloads raw observations and outputs returned by tools/artifacts
     * @return assertion outcome
     */
    public static AssertionOutcome evaluate(
            EvidenceAssertion assertion,
            String finalOutput,
            List<String> verifiedObservationPayloads
    ) {
        if (assertion == null) {
            return AssertionOutcome.pass();
        }

        List<FailureCode> codes = new ArrayList<>();
        List<String> details = new ArrayList<>();

        String text = finalOutput == null ? "" : finalOutput;

        // 1. Structured citation block check
        String citationBlockContent = text;
        if (assertion.requireStructuredCitationBlock()) {
            Matcher blockMatcher = CITATION_BLOCK_PATTERN.matcher(text);
            if (!blockMatcher.find()) {
                codes.add(FailureCode.CITATION_FORMAT_MISSING);
                details.add("Missing required structured citation block '【参考依据】' or '[References]' in final output");
            } else {
                citationBlockContent = blockMatcher.group(1);
            }
        }

        // 2. Extract cited evidence IDs
        Set<String> citedIds = new HashSet<>();
        Matcher idMatcher = EVIDENCE_ID_PATTERN.matcher(citationBlockContent);
        while (idMatcher.find()) {
            citedIds.add(idMatcher.group(1).trim());
        }

        // 3. Check required evidence IDs
        for (String requiredId : assertion.requiredEvidenceIds()) {
            if (!citedIds.contains(requiredId)) {
                codes.add(FailureCode.EVIDENCE_MISSING);
                details.add("Required evidence ID was not cited: " + requiredId + " (cited: " + citedIds + ")");
            }
        }

        // 4. Cross-check against actual observations (reject fabricated IDs)
        if (assertion.rejectFabricatedIds()) {
            String combinedObservations = verifiedObservationPayloads == null ? ""
                    : String.join("\n", verifiedObservationPayloads);

            for (String citedId : citedIds) {
                // Extract identifier part (e.g. "TCK-1001" from "ticket:TCK-1001")
                String specificId = citedId.contains(":") ? citedId.substring(citedId.indexOf(':') + 1) : citedId;
                if (!combinedObservations.contains(citedId) && !combinedObservations.contains(specificId)) {
                    codes.add(FailureCode.EVIDENCE_FABRICATED);
                    details.add("Cited evidence ID '" + citedId + "' was fabricated; not found in any tool observation or artifact payload");
                }
            }
        }

        boolean passed = codes.isEmpty();
        return new AssertionOutcome(passed, codes, details);
    }

    /**
     * Helper to extract cited evidence IDs from text.
     */
    public static Set<String> extractCitedIds(String text) {
        if (text == null) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        Matcher matcher = EVIDENCE_ID_PATTERN.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1).trim());
        }
        return ids;
    }
}
