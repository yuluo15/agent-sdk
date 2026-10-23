package com.agent.agentcorelab.tool;

import java.util.Locale;

/**
 * Three-state indicator for security and governance attributes of an Agent tool.
 * <p>
 * Standard binary booleans cannot differentiate between an explicitly verified attribute
 * and an unverified or unknown claim, especially when dealing with external MCP servers.
 * {@code SemanticHint} provides a safe, explicit taxonomy:
 * <ul>
 *     <li>{@link #YES}: Explicitly verified and guaranteed to possess this property.</li>
 *     <li>{@link #NO}: Explicitly verified and guaranteed NOT to possess this property.</li>
 *     <li>{@link #UNKNOWN}: Unspecified, untrusted, or cannot be formally verified.</li>
 * </ul>
 */
public enum SemanticHint {
    /**
     * Explicitly verified and guaranteed to hold.
     */
    YES,

    /**
     * Explicitly verified and guaranteed NOT to hold.
     */
    NO,

    /**
     * Unspecified, unverified, or untrusted attribute.
     */
    UNKNOWN;

    /**
     * Returns true if this hint is explicitly {@link #YES}.
     */
    public boolean isYes() {
        return this == YES;
    }

    /**
     * Returns true if this hint is explicitly {@link #NO}.
     */
    public boolean isNo() {
        return this == NO;
    }

    /**
     * Returns true if this hint is {@link #UNKNOWN}.
     */
    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * Converts a nullable boolean into a {@link SemanticHint}.
     *
     * @param value boolean or null
     * @return YES for true, NO for false, UNKNOWN for null
     */
    public static SemanticHint fromBoolean(Boolean value) {
        if (value == null) {
            return UNKNOWN;
        }
        return value ? YES : NO;
    }

    /**
     * Parses a string representation safely into a {@link SemanticHint}.
     * <p>
     * Recognizes "yes", "true", "1", "y" as {@link #YES};
     * "no", "false", "0", "n" as {@link #NO};
     * null, blank or unrecognized values as {@link #UNKNOWN}.
     *
     * @param text input string
     * @return parsed semantic hint
     */
    public static SemanticHint parse(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "yes", "true", "1", "y" -> YES;
            case "no", "false", "0", "n" -> NO;
            default -> UNKNOWN;
        };
    }
}
