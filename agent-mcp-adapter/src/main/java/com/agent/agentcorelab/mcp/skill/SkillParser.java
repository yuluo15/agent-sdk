package com.agent.agentcorelab.mcp.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Standard parser for industry-standard {@code SKILL.md} files (agentskills.io specification).
 * <p>
 * Parses YAML Frontmatter enclosed by {@code ---} delimiters followed by the Markdown SOP body.
 */
public final class SkillParser {

    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    private static final Set<String> RESERVED_FRONTMATTER_KEYS = Set.of(
            "name", "id", "description",
            "tags", "category", "categories",
            "prerequisite_tools", "prerequisiteTools", "tools",
            "sop", "metadata"
    );

    private SkillParser() {
    }

    /**
     * Parses a SKILL.md markdown string into a {@link SkillDefinition}.
     *
     * @param markdown the raw markdown content containing YAML frontmatter and SOP body
     * @return the parsed skill definition
     * @throws IllegalArgumentException if the markdown format is invalid or required fields are missing
     */
    public static SkillDefinition parse(String markdown) {
        return parse(markdown, null);
    }

    /**
     * Parses a SKILL.md markdown string into a {@link SkillDefinition}, with an optional fallback ID.
     *
     * @param markdown the raw markdown content
     * @param fallbackId optional fallback ID used if no valid ID or name is defined
     * @return the parsed skill definition
     * @throws IllegalArgumentException if the markdown format is invalid or required fields are missing
     */
    public static SkillDefinition parse(String markdown, String fallbackId) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("Markdown content cannot be null or blank");
        }

        // Strip leading UTF-8 BOM if present
        String content = markdown;
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int openIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals("---")) {
                openIndex = i;
                break;
            } else {
                throw new IllegalArgumentException("Invalid SKILL.md: file must start with YAML frontmatter delimiter '---'");
            }
        }

        if (openIndex == -1) {
            throw new IllegalArgumentException("Invalid SKILL.md: content does not contain frontmatter delimiter '---'");
        }

        int closeIndex = -1;
        for (int i = openIndex + 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closeIndex = i;
                break;
            }
        }

        if (closeIndex == -1) {
            throw new IllegalArgumentException("Invalid SKILL.md: closing frontmatter delimiter '---' not found");
        }

        String yamlContent = String.join("\n", Arrays.copyOfRange(lines, openIndex + 1, closeIndex)).trim();
        if (yamlContent.isEmpty()) {
            throw new IllegalArgumentException("Invalid SKILL.md: frontmatter cannot be empty");
        }

        JsonNode root;
        try {
            root = YAML_MAPPER.readTree(yamlContent);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse YAML frontmatter in SKILL.md: " + e.getMessage(), e);
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid SKILL.md: frontmatter must be a valid YAML object");
        }

        // 1. Resolve ID and Name
        String rawName = getString(root, "name");
        String rawId = getString(root, "id");

        String id;
        String name;

        if (rawId != null && !rawId.isBlank()) {
            id = rawId.trim();
            name = (rawName != null && !rawName.isBlank()) ? rawName.trim() : id;
        } else if (rawName != null && !rawName.isBlank()) {
            name = rawName.trim();
            if (name.matches("[a-zA-Z0-9_-]+")) {
                id = name;
            } else if (fallbackId != null && !fallbackId.isBlank() && fallbackId.matches("[a-zA-Z0-9_-]+")) {
                id = fallbackId.trim();
            } else {
                id = toSlug(name);
                if (id.isEmpty()) {
                    throw new IllegalArgumentException("Skill name '" + name + "' could not be converted to a valid identifier");
                }
            }
        } else if (fallbackId != null && !fallbackId.isBlank()) {
            id = fallbackId.trim();
            name = id;
        } else {
            throw new IllegalArgumentException("SKILL.md frontmatter must specify 'name' or 'id'");
        }

        // 2. Resolve Description
        String description = getString(root, "description");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("SKILL.md frontmatter must specify a non-blank 'description'");
        }

        // 3. Resolve Tags
        List<String> tags = extractStringList(root, "tags", "category", "categories");

        // 4. Resolve Prerequisite Tools
        List<String> prerequisiteTools = extractStringList(root, "prerequisite_tools", "prerequisiteTools", "tools");

        // 5. Resolve Metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (root.has("metadata") && root.get("metadata").isObject()) {
            try {
                Map<String, Object> customMeta = YAML_MAPPER.convertValue(root.get("metadata"), new TypeReference<Map<String, Object>>() {});
                if (customMeta != null) {
                    metadata.putAll(customMeta);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Include any other top-level keys not reserved
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (!RESERVED_FRONTMATTER_KEYS.contains(key) && !metadata.containsKey(key)) {
                JsonNode value = field.getValue();
                if (value.isValueNode()) {
                    if (value.isTextual()) {
                        metadata.put(key, value.asText());
                    } else if (value.isNumber()) {
                        metadata.put(key, value.numberValue());
                    } else if (value.isBoolean()) {
                        metadata.put(key, value.asBoolean());
                    }
                } else if (value.isContainerNode()) {
                    try {
                        Object obj = YAML_MAPPER.convertValue(value, Object.class);
                        metadata.put(key, obj);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        // 6. Resolve SOP body
        String sop = String.join("\n", Arrays.copyOfRange(lines, closeIndex + 1, lines.length)).stripLeading();

        return new SkillDefinition(id, name, description, tags, prerequisiteTools, sop, metadata);
    }

    /**
     * Parses a SKILL.md file from a {@link Path}.
     */
    public static SkillDefinition parseFile(Path path) throws IOException {
        Objects.requireNonNull(path, "path cannot be null");
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String fileName = path.getFileName().toString();
        String fallbackId = null;
        if (path.getParent() != null) {
            fallbackId = path.getParent().getFileName().toString();
        } else if (fileName.contains(".")) {
            fallbackId = fileName.substring(0, fileName.indexOf('.'));
        }
        return parse(content, fallbackId);
    }

    /**
     * Exports a {@link SkillDefinition} into standard SKILL.md format with YAML frontmatter.
     */
    public static String toMarkdown(SkillDefinition skill) {
        Objects.requireNonNull(skill, "skill cannot be null");
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(escapeYamlString(skill.name())).append("\n");
        if (!skill.id().equals(skill.name())) {
            sb.append("id: ").append(escapeYamlString(skill.id())).append("\n");
        }
        sb.append("description: ").append(escapeYamlString(skill.description())).append("\n");

        if (!skill.tags().isEmpty()) {
            sb.append("tags:\n");
            for (String tag : skill.tags()) {
                sb.append("  - ").append(escapeYamlString(tag)).append("\n");
            }
        }

        if (!skill.prerequisiteTools().isEmpty()) {
            sb.append("prerequisite_tools:\n");
            for (String tool : skill.prerequisiteTools()) {
                sb.append("  - ").append(escapeYamlString(tool)).append("\n");
            }
        }

        if (!skill.metadata().isEmpty()) {
            sb.append("metadata:\n");
            for (Map.Entry<String, Object> entry : skill.metadata().entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": ");
                if (entry.getValue() instanceof String s) {
                    sb.append(escapeYamlString(s)).append("\n");
                } else {
                    sb.append(entry.getValue()).append("\n");
                }
            }
        }

        sb.append("---\n\n");
        if (skill.sop() != null && !skill.sop().isBlank()) {
            sb.append(skill.sop().trim()).append("\n");
        }
        return sb.toString();
    }

    private static String getString(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node != null && !node.isNull() && node.isValueNode()) {
            String text = node.asText();
            return text != null && !text.isBlank() ? text.trim() : null;
        }
        return null;
    }

    private static List<String> extractStringList(JsonNode root, String... candidateFields) {
        for (String field : candidateFields) {
            JsonNode node = root.get(field);
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isArray()) {
                List<String> list = new ArrayList<>();
                for (JsonNode item : node) {
                    if (item.isValueNode() && !item.asText().isBlank()) {
                        list.add(item.asText().trim());
                    }
                }
                return Collections.unmodifiableList(list);
            } else if (node.isValueNode() && !node.asText().isBlank()) {
                String text = node.asText().trim();
                String[] parts = text.split("[,;]");
                List<String> list = new ArrayList<>();
                for (String part : parts) {
                    if (!part.isBlank()) {
                        list.add(part.trim());
                    }
                }
                return Collections.unmodifiableList(list);
            }
        }
        return List.of();
    }

    private static String toSlug(String input) {
        if (input == null) {
            return "";
        }
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static String escapeYamlString(String val) {
        if (val == null || val.isEmpty()) {
            return "\"\"";
        }
        if (val.matches("^-?\\d+(\\.\\d+)?$")
                || "true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)
                || "yes".equalsIgnoreCase(val) || "no".equalsIgnoreCase(val)
                || "null".equalsIgnoreCase(val)) {
            return "\"" + val + "\"";
        }
        if (val.contains(":") || val.contains("#") || val.contains("\"") || val.contains("\n")
                || val.contains("'") || val.startsWith("-") || val.startsWith("@") || val.startsWith("`")) {
            return "\"" + val.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        return val;
    }
}
