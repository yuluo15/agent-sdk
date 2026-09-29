package com.agent.agentcorelab.mcp.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a standardized enterprise capability or Standard Operating Procedure (SOP).
 * A skill bundles task-level guidelines, checklists, and prerequisite tools.
 */
public record SkillDefinition(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> prerequisiteTools,
        String sop,
        Map<String, Object> metadata
) {
    public SkillDefinition {
        Objects.requireNonNull(id, "id cannot be null");
        if (!id.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Skill id must contain only letters, numbers, underscores, or hyphens: " + id);
        }
        Objects.requireNonNull(description, "description cannot be null");
        name = (name != null && !name.isBlank()) ? name : id;
        tags = tags != null ? List.copyOf(tags) : List.of();
        prerequisiteTools = prerequisiteTools != null ? List.copyOf(prerequisiteTools) : List.of();
        sop = sop != null ? sop : "";
        metadata = metadata != null ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata)) : Map.of();
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private List<String> tags = List.of();
        private List<String> prerequisiteTools = List.of();
        private String sop = "";
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags != null ? tags : List.of();
            return this;
        }

        public Builder tags(String... tags) {
            this.tags = tags != null ? List.of(tags) : List.of();
            return this;
        }

        public Builder prerequisiteTools(List<String> tools) {
            this.prerequisiteTools = tools != null ? tools : List.of();
            return this;
        }

        public Builder prerequisiteTools(String... tools) {
            this.prerequisiteTools = tools != null ? List.of(tools) : List.of();
            return this;
        }

        public Builder sop(String sop) {
            this.sop = sop != null ? sop : "";
            return this;
        }

        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata != null) {
                this.metadata.putAll(metadata);
            }
            return this;
        }

        public SkillDefinition build() {
            return new SkillDefinition(id, name, description, tags, prerequisiteTools, sop, metadata);
        }
    }
}
