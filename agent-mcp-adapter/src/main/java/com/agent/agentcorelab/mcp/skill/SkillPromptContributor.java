package com.agent.agentcorelab.mcp.skill;

import java.util.List;
import java.util.Objects;

/**
 * Generates concise markdown prompt contributions advertising available enterprise skills
 * to the LLM agent without bloating the base system prompt.
 */
public final class SkillPromptContributor {

    private SkillPromptContributor() {
    }

    /**
     * Renders a markdown block listing available skills and how to consult their SOPs.
     */
    public static String renderSkillIndex(SkillRegistry registry) {
        Objects.requireNonNull(registry, "registry cannot be null");
        List<SkillDefinition> skills = registry.listAll();
        if (skills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### Enterprise Skills & SOPs Available\n");
        sb.append("You have access to standardized enterprise procedures. Before performing relevant tasks, call `consult_skill` with the corresponding `skillId` to retrieve its detailed Standard Operating Procedure (SOP) and checklist:\n");

        for (SkillDefinition skill : skills) {
            sb.append("- **`").append(skill.id()).append("`** (").append(skill.name()).append("): ")
              .append(skill.description());
            if (!skill.prerequisiteTools().isEmpty()) {
                sb.append(" [Requires tools: ").append(String.join(", ", skill.prerequisiteTools())).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
