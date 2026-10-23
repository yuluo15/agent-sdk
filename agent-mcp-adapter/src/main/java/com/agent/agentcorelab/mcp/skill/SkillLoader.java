package com.agent.agentcorelab.mcp.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * File system scanner and loader for {@code SKILL.md} enterprise skill files.
 * <p>
 * Scans directories recursively or individually for skills following the agentskills.io standard.
 */
public final class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final Predicate<Path> DEFAULT_SKILL_FILE_FILTER = path -> {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return fileName.equalsIgnoreCase("SKILL.md")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".skill.md");
    };

    private SkillLoader() {
    }

    /**
     * Loads a single {@link SkillDefinition} from a file path.
     *
     * @param path file path
     * @return parsed skill definition
     * @throws UncheckedIOException if an I/O error occurs reading the file
     */
    public static SkillDefinition loadFromFile(Path path) {
        Objects.requireNonNull(path, "path cannot be null");
        try {
            return SkillParser.parseFile(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read skill file: " + path, e);
        }
    }

    /**
     * Recursively scans the directory for {@code SKILL.md} and {@code *.skill.md} files.
     *
     * @param directory directory to scan
     * @return unmodifiable list of loaded skill definitions, sorted by ID
     */
    public static List<SkillDefinition> loadFromDirectory(Path directory) {
        return loadFromDirectory(directory, true, DEFAULT_SKILL_FILE_FILTER, false);
    }

    /**
     * Scans the directory with custom options.
     *
     * @param directory directory to scan
     * @param recursive whether to recursively traverse subdirectories
     * @param filter file filter predicate
     * @param failOnError whether to fail fast on invalid files
     * @return unmodifiable list of loaded skill definitions, sorted by ID
     */
    public static List<SkillDefinition> loadFromDirectory(Path directory, boolean recursive, Predicate<Path> filter, boolean failOnError) {
        Objects.requireNonNull(directory, "directory cannot be null");
        if (!Files.exists(directory)) {
            log.warn("Skills directory does not exist: {}", directory.toAbsolutePath());
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory: " + directory.toAbsolutePath());
        }

        int maxDepth = recursive ? Integer.MAX_VALUE : 2;
        Predicate<Path> actualFilter = filter != null ? filter : DEFAULT_SKILL_FILE_FILTER;

        List<SkillDefinition> skills = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, maxDepth)) {
            List<Path> matchingFiles = stream.filter(actualFilter).toList();
            for (Path file : matchingFiles) {
                try {
                    SkillDefinition skill = loadFromFile(file);
                    skills.add(skill);
                    log.debug("Loaded enterprise skill '{}' from {}", skill.id(), file.toAbsolutePath());
                } catch (Exception e) {
                    if (failOnError) {
                        throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                    }
                    log.warn("Failed to parse skill file {}: {}", file.toAbsolutePath(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan directory for skills: " + directory, e);
        }

        skills.sort(Comparator.comparing(SkillDefinition::id));
        return Collections.unmodifiableList(skills);
    }
}
