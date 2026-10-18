package com.agent.agentcorelab.eval.dataset;

/**
 * Version metadata associated with an evaluation case or dataset release.
 */
public record EvalVersions(
        String model,
        String promptVersion,
        String toolSchemaVersion,
        String datasetVersion
) {
    public static EvalVersions empty() {
        return new EvalVersions("unknown", "default", "default", "v1.0");
    }
}
