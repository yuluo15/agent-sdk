package com.agent.agentcorelab.sample.files;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/** A listing (including a genuinely empty listing) is sufficient; no report is required. */
public final class FileInspectionCompletionCheck implements CompletionCheck {
    @Override public Result check(RunSnapshot run, String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) return new Result(false, "Provide a nonempty final answer");
        for (RunSnapshot.Observation observation : run.observations()) {
            if (!observation.call().name().equals("listWorkspaceFiles")
                    || !(observation.outcome() instanceof ToolOutcome.Success success)) continue;
            try {
                JsonNode args = WorkspaceJson.parse(observation.call().argumentsJson());
                JsonNode result = WorkspaceJson.parse(success.outputJson());
                if (args.isObject() && args.isEmpty() && result.path("ok").asBoolean(false)
                        && result.path("runId").asText().equals(run.runId().toString())
                        && result.path("tool").asText().equals("listWorkspaceFiles")
                        && result.path("sources").equals(args)
                        && !result.path("evidenceId").asText().isBlank()
                        && result.path("data").path("files").isArray()
                        && result.path("data").path("files").size() <= 20
                        && validFiles(result.path("data").path("files"), result.path("files"))) {
                    return new Result(true, "");
                }
            } catch (IOException | RuntimeException ignored) {
                // Malformed or unrelated observations are not proof of execution.
            }
        }
        return new Result(false, "Call listWorkspaceFiles successfully in this run before finishing; no report is needed");
    }

    private boolean validFiles(JsonNode entries, JsonNode sources) {
        if (!sources.isArray() || entries.size() != sources.size()) return false;
        for (JsonNode file : entries) {
            if (!file.path("fileId").asText().startsWith("f_") || file.path("name").asText().isBlank()
                    || !file.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !file.path("bytes").isIntegralNumber() || file.path("bytes").asLong() < 0) return false;
            boolean found = false;
            for (JsonNode source : sources) if (file.equals(source)) found = true;
            if (!found) return false;
        }
        return true;
    }
}
