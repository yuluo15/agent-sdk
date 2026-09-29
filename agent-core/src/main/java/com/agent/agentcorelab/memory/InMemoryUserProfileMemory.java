package com.agent.agentcorelab.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, bounded in-memory implementation of {@link UserProfileMemory}.
 * Uses an LRU cache to evict least-recently accessed profiles when exceeding maximum capacity.
 */
public final class InMemoryUserProfileMemory implements UserProfileMemory {

    public static final int DEFAULT_MAX_PROFILES = 2000;

    private final int maxProfiles;
    private final Map<String, Map<String, String>> profileStore;

    public InMemoryUserProfileMemory() {
        this(DEFAULT_MAX_PROFILES);
    }

    public InMemoryUserProfileMemory(int maxProfiles) {
        if (maxProfiles <= 0) {
            throw new IllegalArgumentException("maxProfiles must be strictly positive, got: " + maxProfiles);
        }
        this.maxProfiles = maxProfiles;
        this.profileStore = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                return size() > InMemoryUserProfileMemory.this.maxProfiles;
            }
        };
    }

    @Override
    public synchronized void setAttribute(String userId, String key, String value) {
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(key, "key cannot be null");

        Map<String, String> userAttrs = profileStore.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        if (value == null) {
            userAttrs.remove(key);
        } else {
            userAttrs.put(key, value);
        }
    }

    @Override
    public synchronized void setAttributes(String userId, Map<String, String> attributes) {
        Objects.requireNonNull(userId, "userId cannot be null");
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        Map<String, String> userAttrs = profileStore.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getKey() != null) {
                if (entry.getValue() == null) {
                    userAttrs.remove(entry.getKey());
                } else {
                    userAttrs.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @Override
    public synchronized Optional<String> getAttribute(String userId, String key) {
        if (userId == null || key == null) {
            return Optional.empty();
        }
        Map<String, String> userAttrs = profileStore.get(userId);
        if (userAttrs == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(userAttrs.get(key));
    }

    @Override
    public synchronized Map<String, String> getProfile(String userId) {
        if (userId == null) {
            return Map.of();
        }
        Map<String, String> userAttrs = profileStore.get(userId);
        if (userAttrs == null || userAttrs.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(userAttrs));
    }

    @Override
    public synchronized void clearProfile(String userId) {
        if (userId != null) {
            profileStore.remove(userId);
        }
    }

    @Override
    public synchronized int activeProfileCount() {
        return profileStore.size();
    }
}
