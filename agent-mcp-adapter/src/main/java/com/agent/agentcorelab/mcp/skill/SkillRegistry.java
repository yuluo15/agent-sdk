package com.agent.agentcorelab.mcp.skill;

import java.util.List;
import java.util.Optional;

/**
 * Registry interface for managing and searching enterprise {@link SkillDefinition} instances.
 */
public interface SkillRegistry {

    /**
     * Registers a new skill definition into the registry.
     */
    void register(SkillDefinition skill);

    /**
     * Retrieves a skill definition by its unique identifier.
     */
    Optional<SkillDefinition> get(String id);

    /**
     * Returns an unmodifiable list of all registered skills.
     */
    List<SkillDefinition> listAll();

    /**
     * Finds all skills matching the specified tag.
     */
    List<SkillDefinition> findByTag(String tag);

    /**
     * Searches for skills whose ID, name, description, or tags contain the given keyword (case-insensitive).
     */
    List<SkillDefinition> search(String keyword);

    /**
     * Checks if a skill with the specified ID is registered.
     */
    boolean hasSkill(String id);
}
