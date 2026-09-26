package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.RunSnapshot;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Verifies the latest actual delivery, never an unreferenced earlier correct analysis. */
public final class AnalysisCompletionCheck implements CompletionCheck {
    private final ComparisonTarget target;

    public AnalysisCompletionCheck() { this(ComparisonTarget.exercise()); }
    public AnalysisCompletionCheck(ComparisonTarget target) { this.target = Objects.requireNonNull(target); }

    @Override public Result check(RunSnapshot run, String finalAnswer) {
        if (finalAnswer == null || finalAnswer.isBlank()) return fail("Provide a nonempty final answer");
        try {
            Map<String, Observed> observed = new HashMap<>();
            Observed latestWrite = null;
            Map<String, Observed> atLatestWrite = Map.of();
            for (RunSnapshot.Observation observation : run.observations()) {
                if (!(observation.outcome() instanceof ToolOutcome.Success success)) continue;
                JsonNode result = WorkspaceJson.parse(success.outputJson());
                if (!result.path("ok").asBoolean(false)
                        || !run.runId().toString().equals(result.path("runId").asText())
                        || !observation.call().name().equals(result.path("tool").asText())
                        || !result.path("evidenceId").asText().matches("e_[0-9a-f-]{36}")) continue;
                Observed entry = new Observed(observation.call().name(), WorkspaceJson.parse(observation.call().argumentsJson()), result);
                if (entry.tool().equals("writeReport")) {
                    latestWrite = entry;
                    atLatestWrite = new HashMap<>(observed);
                }
                String id = result.get("evidenceId").asText();
                if (observed.putIfAbsent(id, entry) != null) return fail("Evidence identifiers must be unique");
            }
            if (latestWrite == null) return fail("Write a real report citing this run's analysis and reference evidence");
            String artifactId = latestWrite.result().path("data").path("artifactId").asText();
            Artifact artifact = run.artifacts().isEmpty() ? null : run.artifacts().getLast();
            if (artifact == null || !artifact.id().equals(artifactId) || artifact.path().getParent() == null
                    || !artifact.path().getParent().getFileName().toString().equals(run.runId().toString())
                    || !artifact.contentDigest().equals(latestWrite.result().path("data").path("sha256").asText())) {
                return fail("Latest report must refer to a real artifact owned by this run");
            }
            WorkspaceSession.verifyArtifact(artifact);
            List<String> requested = strings(latestWrite.arguments().path("evidenceIds"));
            List<String> normalized = new TreeSet<>(requested).stream().toList();
            if (normalized.isEmpty() || !normalized.equals(artifact.evidenceIds())
                    || !normalized.equals(strings(latestWrite.result().path("data").path("evidenceIds")))) {
                return fail("Latest report citations do not match the real artifact");
            }
            List<WorkspaceSession.Evidence> refs = new ArrayList<>();
            boolean targetAnalysis = false;
            boolean reference = false;
            for (String id : normalized) {
                Observed entry = atLatestWrite.get(id);
                if (entry == null) return fail("Report cites evidence not successfully observed before this write in this run");
                WorkspaceSession.Evidence evidence = evidence(entry.result());
                refs.add(evidence);
                if (entry.tool().equals("readReference") && validReference(entry)) reference = true;
                if (entry.tool().equals("analyzeDataset") && matchesTarget(entry)
                        && inspectedAndDiscovered(entry, atLatestWrite)) targetAnalysis = true;
            }
            if (!targetAnalysis) return fail("Latest report must actually cite an inspected, full-group analysis with the configured dates and gross/refund mappings");
            if (!reference) return fail("Latest report must cite a successful nonempty reference reading for metric definitions and limitations");
            String title = latestWrite.arguments().path("title").asText();
            String body = latestWrite.arguments().path("bodyMarkdown").asText();
            if (title.isBlank() || body.isBlank()) return fail("Report title and body must be nonempty");
            String expected = WorkspaceSession.renderReport(title, body, refs);
            if (!WorkspaceSession.digest(expected.getBytes(StandardCharsets.UTF_8)).equals(artifact.contentDigest())) {
                return fail("Report bytes must match the actual body and program-generated evidence appendix");
            }
            return new Result(true, "");
        } catch (IOException | RuntimeException e) {
            return fail("Latest report, source observations or content digest could not be verified");
        }
    }

    private boolean matchesTarget(Observed entry) {
        JsonNode sources = entry.result().path("sources");
        if (!target.baseline().matches(sources.path("baseline")) || !target.comparison().matches(sources.path("comparison"))
                || sources.has("groups") || entry.arguments().has("groups")) return false;
        ObjectNode actualArguments = sources.deepCopy();
        for (String name : List.of("baseline", "comparison")) {
            JsonNode side = actualArguments.get(name);
            if (!(side instanceof ObjectNode object)) return false;
            object.remove(List.of("sha256", "name"));
        }
        if (!actualArguments.equals(entry.arguments())) return false;
        JsonNode data = entry.result().path("data");
        if (!"ALL_GROUPS".equals(data.path("totals").path("scope").asText())
                || !data.path("groups").isArray() || data.path("groups").isEmpty() || data.path("groups").size() > 10
                || !data.path("ranking").isArray()) return false;
        for (String side : List.of("baseline", "comparison")) {
            if (data.path("coverage").path(side).path("matchedRows").asInt() <= 0
                    || !sourcePresent(sources.path(side), entry.result().path("files"))) return false;
        }
        return true;
    }

    private boolean inspectedAndDiscovered(Observed analysis, Map<String, Observed> observations) {
        for (String side : List.of("baseline", "comparison")) {
            JsonNode source = analysis.result().path("sources").path(side);
            boolean inspected = false;
            boolean discovered = false;
            for (Observed observed : observations.values()) {
                JsonNode result = observed.result();
                if (observed.tool().equals("listWorkspaceFiles") && sourcePresent(source, result.path("data").path("files"))
                        && observed.arguments().isObject() && observed.arguments().isEmpty()) discovered = true;
                if (observed.tool().equals("inspectDataset")
                        && source.path("fileId").asText().equals(observed.arguments().path("fileId").asText())
                        && result.path("sources").equals(observed.arguments())
                        && sourcePresent(source, result.path("files"))
                        && source.path("sha256").asText().equals(result.path("data").path("sha256").asText())) {
                    Set<String> columns = new HashSet<>(strings(result.path("data").path("columns")));
                    inspected = List.of("dateColumn", "groupColumn", "valueColumn", "deductionColumn", "countColumn")
                            .stream().filter(source::has).allMatch(field -> columns.contains(source.path(field).asText()));
                }
            }
            if (!inspected || !discovered) return false;
        }
        return true;
    }

    private boolean validReference(Observed entry) {
        JsonNode sources = entry.result().path("sources");
        JsonNode data = entry.result().path("data");
        JsonNode args = entry.arguments();
        if (!sources.path("fileId").asText().equals(args.path("fileId").asText())
                || sources.path("startLine").asInt() != args.path("startLine").asInt(1)
                || sources.path("maxLines").asInt() != args.path("maxLines").asInt(60)
                || !data.path("lines").isArray() || data.path("lines").isEmpty()) return false;
        ObjectNode source = WorkspaceJson.object().put("fileId", args.path("fileId").asText()).put("sha256", data.path("sha256").asText());
        return sourcePresent(source, entry.result().path("files"))
                && stringsOfReference(data.path("lines"));
    }

    private boolean stringsOfReference(JsonNode lines) {
        for (JsonNode line : lines) if (line.path("line").asInt() > 0 && !line.path("text").asText().isBlank()) return true;
        return false;
    }

    private static boolean sourcePresent(JsonNode source, JsonNode files) {
        if (!source.path("sha256").asText().matches("[0-9a-f]{64}") || !files.isArray()) return false;
        for (JsonNode file : files) {
            if (source.path("fileId").equals(file.path("fileId")) && source.path("sha256").equals(file.path("sha256"))
                    && (!source.has("name") || source.path("name").equals(file.path("name")))) return true;
        }
        return false;
    }

    private static WorkspaceSession.Evidence evidence(JsonNode result) {
        List<WorkspaceSession.FileEntry> files = new ArrayList<>();
        for (JsonNode file : result.path("files")) {
            files.add(new WorkspaceSession.FileEntry(file.path("fileId").asText(), file.path("name").asText(),
                    file.path("type").asText(), file.path("bytes").asLong(), file.path("sha256").asText()));
        }
        return new WorkspaceSession.Evidence(result.path("evidenceId").asText(), result.path("tool").asText(),
                result.path("sources"), result.path("data"), files);
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Expected an array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (!item.isTextual()) throw new IllegalArgumentException("Expected string items");
            result.add(item.asText());
        }
        return result;
    }

    private static Result fail(String feedback) { return new Result(false, feedback); }
    private record Observed(String tool, JsonNode arguments, JsonNode result) { }
}
