package com.agent.agentcorelab.model;

import java.nio.charset.StandardCharsets;

/**
 * Estimates serialized context tokens for an LLM model before dispatching requests.
 * The model gateway adds message and tool framing tokens separately.
 * <p>
 * <b>Operational Note:</b> This estimation is used as an operational defense barrier to prevent
 * context window overflow and trigger proactive compaction or workspace spillover. It is
 * <i>an estimation, not an exact meter</i>. Official billing and token accounting are always
 * governed by the {@code usage} metadata returned by the upstream model provider.
 */
@FunctionalInterface
public interface TokenEstimator {

    /**
     * Estimates the token count for a given serialized context string.
     *
     * @param model the target LLM model name
     * @param contextJson the raw or serialized JSON context content
     * @return non-negative estimated token count
     */
    long estimate(String model, String contextJson);

    /**
     * Smart character-weighted token estimator optimized for mixed CJK (Chinese, Japanese, Korean)
     * and ASCII/Latin text.
     * <p>
     * Default parameters:
     * <ul>
     *   <li>ASCII weight (&le; 127): ~0.25 tokens/char (~4 characters per token in modern BPE)</li>
     *   <li>Non-ASCII weight (&gt; 127): ~0.70 tokens/char (typical for CJK ideographs in modern BPE)</li>
     *   <li>Safety buffer multiplier: 1.15 (+15% cushion for prompt framing, whitespace, and special tokens)</li>
     * </ul>
     */
    static TokenEstimator characterWeighted() {
        return characterWeighted(0.25, 0.70, 1.15);
    }

    /**
     * Configurable character-weighted token estimator.
     *
     * @param asciiWeight weight per ASCII character (&le; 127)
     * @param nonAsciiWeight weight per non-ASCII/CJK character (&gt; 127)
     * @param safetyBuffer safety cushion multiplier applied to the raw sum
     * @return configured TokenEstimator
     */
    static TokenEstimator characterWeighted(double asciiWeight, double nonAsciiWeight, double safetyBuffer) {
        if (Double.isNaN(asciiWeight) || Double.isInfinite(asciiWeight) || asciiWeight <= 0) {
            throw new IllegalArgumentException("asciiWeight must be a positive finite number, got: " + asciiWeight);
        }
        if (Double.isNaN(nonAsciiWeight) || Double.isInfinite(nonAsciiWeight) || nonAsciiWeight <= 0) {
            throw new IllegalArgumentException("nonAsciiWeight must be a positive finite number, got: " + nonAsciiWeight);
        }
        if (Double.isNaN(safetyBuffer) || Double.isInfinite(safetyBuffer) || safetyBuffer <= 0) {
            throw new IllegalArgumentException("safetyBuffer must be a positive finite number, got: " + safetyBuffer);
        }

        return (model, contextJson) -> {
            if (contextJson == null || contextJson.isEmpty()) {
                return 0L;
            }
            long asciiCount = 0;
            long nonAsciiCount = 0;
            int length = contextJson.length();
            for (int i = 0; i < length; i++) {
                char c = contextJson.charAt(i);
                if (c <= 127) {
                    asciiCount++;
                } else {
                    nonAsciiCount++;
                }
            }
            double estimated = (asciiCount * asciiWeight) + (nonAsciiCount * nonAsciiWeight);
            return Math.max(1L, (long) Math.ceil(estimated * safetyBuffer));
        };
    }

    /**
     * Legacy byte-based estimator: counts raw UTF-8 bytes (1 byte = 1 token).
     * Conservative for ASCII, but heavily over-estimates multi-byte CJK/Chinese text (3 tokens/char).
     * Retained for backward compatibility.
     */
    static TokenEstimator conservativeUtf8() {
        return (model, contextJson) -> contextJson == null ? 0L : contextJson.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Returns the recommended default token estimator for the SDK runtime.
     */
    static TokenEstimator defaultEstimator() {
        return characterWeighted();
    }
}
