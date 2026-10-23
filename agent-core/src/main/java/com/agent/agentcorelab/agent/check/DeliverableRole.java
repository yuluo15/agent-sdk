package com.agent.agentcorelab.agent.check;

/**
 * Categorical logical role of a task deliverable, decoupling verification from physical file names.
 */
public enum DeliverableRole {
    /**
     * Analytical, summarization, or investigation report (typically Markdown, HTML, or PDF).
     */
    REPORT,

    /**
     * Structured data export, tabular results, CSV, JSON dump, or parquet file.
     */
    DATASET,

    /**
     * Source code, scripts, migration files, or patches.
     */
    CODE,

    /**
     * Configuration, deployment descriptors, YAML, properties, or environment files.
     */
    CONFIG,

    /**
     * Unclassified or domain-specific physical deliverable.
     */
    OTHER;

    /**
     * Heuristically infers the logical deliverable role from a file name or file extension.
     */
    public static DeliverableRole inferFromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return OTHER;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".html") || lower.endsWith(".pdf") || lower.contains("report")) {
            return REPORT;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".parquet")
                || (lower.endsWith(".json") && (lower.contains("data") || lower.contains("dataset") || lower.contains("export")))) {
            return DATASET;
        }
        if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".sh") || lower.endsWith(".sql") || lower.endsWith(".diff") || lower.endsWith(".patch")) {
            return CODE;
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".properties")
                || lower.endsWith(".conf") || lower.endsWith(".toml") || lower.endsWith(".env") || lower.endsWith(".xml")) {
            return CONFIG;
        }
        return OTHER;
    }
}
