package com.agent.agentcorelab.memory;

import java.util.Map;
import java.util.Optional;

/**
 * Service SPI for managing cross-session long-term user profile attributes and personalization preferences.
 * <p>
 * Enables agents to retain user preferences (e.g., room preferences, analytical metric defaults,
 * communication styles) across different conversation runs and browser sessions.
 */
public interface UserProfileMemory {

    /**
     * Sets or updates a single profile attribute for the given user.
     *
     * @param userId unique user identifier
     * @param key    attribute key
     * @param value  attribute value (null to remove)
     */
    void setAttribute(String userId, String key, String value);

    /**
     * Sets or updates multiple profile attributes for the given user.
     *
     * @param userId     unique user identifier
     * @param attributes attribute key-value map
     */
    void setAttributes(String userId, Map<String, String> attributes);

    /**
     * Retrieves a single attribute value for the given user.
     *
     * @param userId unique user identifier
     * @param key    attribute key
     * @return optional attribute value
     */
    Optional<String> getAttribute(String userId, String key);

    /**
     * Retrieves an unmodifiable snapshot map of all attributes for the given user.
     *
     * @param userId unique user identifier
     * @return attribute key-value map (empty if user has no stored profile)
     */
    Map<String, String> getProfile(String userId);

    /**
     * Clears all stored profile attributes for the given user.
     *
     * @param userId unique user identifier
     */
    void clearProfile(String userId);

    /**
     * Returns the count of distinct active user profiles tracked.
     */
    int activeProfileCount();
}
