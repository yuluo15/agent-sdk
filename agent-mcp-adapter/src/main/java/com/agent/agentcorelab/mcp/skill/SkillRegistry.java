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

    /**
     * Loads and registers all SKILL.md enterprise skill definitions found in the specified directory.
     *
     * @param directory directory containing skill files
     * @return unmodifiable list of newly registered skills
     */
    List<SkillDefinition> loadFromDirectory(java.nio.file.Path directory);

    /**
     * Loads and registers a skill definition from a SKILL.md file.
     *
     * @param file path to the SKILL.md file
     * @return the parsed and registered skill definition
     */
    SkillDefinition loadFromFile(java.nio.file.Path file);

    /**
     * Parses and registers a skill from standard SKILL.md markdown text.
     *
     * @param markdown markdown content containing YAML frontmatter and SOP body
     * @return the parsed and registered skill definition
     */
    SkillDefinition registerSkillMd(String markdown);
}
