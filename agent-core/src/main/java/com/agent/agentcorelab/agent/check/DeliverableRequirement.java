package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.workspace.Artifact;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Specification of a required logical deliverable, decoupling verification from rigid physical file paths.
 */
public record DeliverableRequirement(
        String id,
        DeliverableRole role,
        String description,
        boolean required,
        long minBytes,
        String fileExtension
) {
    public DeliverableRequirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        description = description != null ? description : "";
        fileExtension = normalizeExtension(fileExtension);
        if (minBytes < 0) {
            minBytes = 0;
        }
    }

    public DeliverableRequirement(String id, DeliverableRole role, String description) {
        this(id, role, description, true, 1L, "");
    }

    public DeliverableRequirement(String id, DeliverableRole role, String description, String fileExtension) {
        this(id, role, description, true, 1L, fileExtension);
    }

    public static DeliverableRequirement report(String id, String description) {
        return new DeliverableRequirement(id, DeliverableRole.REPORT, description, true, 1L, ".md");
    }

    public static DeliverableRequirement dataset(String id, String description, String fileExtension) {
        return new DeliverableRequirement(id, DeliverableRole.DATASET, description, true, 1L, fileExtension);
    }

    public static DeliverableRequirement code(String id, String description, String fileExtension) {
        return new DeliverableRequirement(id, DeliverableRole.CODE, description, true, 1L, fileExtension);
    }

    public static DeliverableRequirement config(String id, String description, String fileExtension) {
        return new DeliverableRequirement(id, DeliverableRole.CONFIG, description, true, 1L, fileExtension);
    }

    private static String normalizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return "";
        }
        String trimmed = ext.trim().toLowerCase();
        return trimmed.startsWith(".") ? trimmed : "." + trimmed;
    }

    /**
     * Determines whether an artifact fulfills this logical requirement.
     */
    public boolean matches(Artifact artifact) {
        if (artifact == null) {
            return false;
        }
        if (artifact.id() != null && artifact.id().equalsIgnoreCase(this.id)) {
            return true;
        }
        Path path = artifact.path();
        if (path == null) {
            return false;
        }
        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "";
        String lowerFileName = fileName.toLowerCase();

        if (!fileExtension.isEmpty() && !lowerFileName.endsWith(fileExtension)) {
            return false;
        }

        if (!fileName.isEmpty()) {
            if (fileName.equalsIgnoreCase(this.id) || lowerFileName.startsWith(this.id.toLowerCase())) {
                return true;
            }
            int dot = fileName.lastIndexOf('.');
            String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (baseName.equalsIgnoreCase(this.id)) {
                return true;
            }
        }

        DeliverableRole inferred = DeliverableRole.inferFromFileName(fileName);
        return this.role == inferred;
    }
}
