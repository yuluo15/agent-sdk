package com.agent.agentcorelab.mcp.skill;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe default implementation of {@link SkillRegistry}.
 */
public final class DefaultSkillRegistry implements SkillRegistry {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public DefaultSkillRegistry() {
    }

    public DefaultSkillRegistry(Collection<SkillDefinition> initialSkills) {
        if (initialSkills != null) {
            for (SkillDefinition skill : initialSkills) {
                register(skill);
            }
        }
    }

    @Override
    public void register(SkillDefinition skill) {
        Objects.requireNonNull(skill, "skill cannot be null");
        skills.put(skill.id(), skill);
    }

    @Override
    public Optional<SkillDefinition> get(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(id));
    }

    @Override
    public List<SkillDefinition> listAll() {
        return List.copyOf(skills.values());
    }

    @Override
    public List<SkillDefinition> findByTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        String normalized = tag.trim().toLowerCase(Locale.ROOT);
        return skills.values().stream()
                .filter(s -> s.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).equals(normalized)))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    @Override
    public List<SkillDefinition> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listAll();
        }
        String query = keyword.toLowerCase(Locale.ROOT).trim();
        return skills.values().stream()
                .filter(s -> s.id().toLowerCase(Locale.ROOT).contains(query)
                        || s.name().toLowerCase(Locale.ROOT).contains(query)
                        || s.description().toLowerCase(Locale.ROOT).contains(query)
                        || s.tags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(query)))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
    }

    @Override
    public boolean hasSkill(String id) {
        return id != null && skills.containsKey(id);
    }
}
