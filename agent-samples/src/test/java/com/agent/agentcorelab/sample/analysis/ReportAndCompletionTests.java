package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.tool.ActiveTimeExceededException;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReportAndCompletionTests {
    @TempDir Path root;

    @Test void reportIsRealHasExactEvidenceAppendixAndCompletesIndependently() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            JsonNode result = run.success("writeReport", run.report("两个指定两日窗口，线上净额下降；不推断外部因果。", refs.toArray(String[]::new)));
            Artifact artifact = run.artifacts.getLast();
            assertEquals(artifact.id(), result.path("data").path("artifactId").asText());
            assertEquals("report-001.md", artifact.path().getFileName().toString());
            assertEquals(run.runId + "/report-001.md", result.path("data").path("relativePath").asText());
            assertEquals(artifact.contentDigest(), WorkspaceSession.digest(Files.readAllBytes(artifact.path())));
            String report = Files.readString(artifact.path());
            assertTrue(report.contains(WorkspaceSession.APPENDIX_MARKER));
            for (String id : refs) assertTrue(report.contains(id));
            assertTrue(report.contains("sales-baseline.csv"));
            assertTrue(report.contains("refund_amount"));
            assertTrue(report.contains("-400.00"));
            assertTrue(report.contains("2026-09-02"));
            assertTrue(run.scenario.completionCheck().check(run.snapshot(), "报告已生成").complete());
            assertFalse(run.scenario.completionCheck().check(run.snapshot(), " ").complete());
        }
    }

    @Test void reportDeduplicatesNormalizedReferencesWithoutOverwritingAndNewContentCreatesNewArtifact() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            run.success("writeReport", run.report("正文", refs.get(0), refs.get(1)));
            Artifact first = run.artifacts.getLast();
            byte[] firstBytes = Files.readAllBytes(first.path());
            run.success("writeReport", run.report("正文", refs.get(1), refs.get(0), refs.get(0)));
            assertEquals(first, run.artifacts.getLast());
            try (var files = Files.list(first.path().getParent())) { assertEquals(1, files.count()); }
            run.success("writeReport", run.report("修改后的正文", refs.toArray(String[]::new)));
            Artifact second = run.artifacts.getLast();
            assertNotEquals(first.id(), second.id());
            assertEquals("report-002.md", second.path().getFileName().toString());
            assertArrayEquals(firstBytes, Files.readAllBytes(first.path()));
            run.success("writeReport", run.report("正文", refs.toArray(String[]::new)));
            assertEquals(first, run.session.latestArtifact());
            assertTrue(run.scenario.completionCheck().check(run.snapshot(), "重新交付原正文").complete());
        }
    }

    @Test void fakeOrCrossRunEvidenceAndMissingAnalysisCannotCreateArtifacts() throws Exception {
        try (SampleRun run = new SampleRun(root, true); SampleRun other = new SampleRun(root.resolve("other"), true)) {
            List<String> otherRefs = other.prepareReport();
            run.discover();
            assertEquals("UNKNOWN_EVIDENCE_ID", run.failure("writeReport", run.report("正文", "e_" + UUID.randomUUID())));
            assertEquals("UNKNOWN_EVIDENCE_ID", run.failure("writeReport", run.report("正文", otherRefs.getFirst())));
            String reference = run.success("readReference", run.file("metric-notes.txt")).get("evidenceId").asText();
            assertEquals("ANALYSIS_REQUIRED", run.failure("writeReport", run.report("正文", reference)));
            assertTrue(run.artifacts.isEmpty());
        }
    }

    @Test void changedSourceBlocksWriteWithTerminalFailure() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            Files.writeString(run.workspace.inputRoot().resolve("sales-current.csv"), "changed");
            ToolOutcome.Failure failure = assertInstanceOf(ToolOutcome.Failure.class,
                    run.call("writeReport", run.report("正文", refs.toArray(String[]::new))));
            assertEquals("INPUT_CHANGED", failure.code());
            assertTrue(failure.terminal());
            assertTrue(run.artifacts.isEmpty());
        }
    }

    @Test void bodyByteLimitAndTitlePathFieldsAreBusinessBoundaries() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            assertEquals("INVALID_ARGUMENT", run.failure("writeReport", run.report("数".repeat(8193), refs.toArray(String[]::new))));
            assertEquals("INVALID_ARGUMENT", run.failure("writeReport", run.report(" ", refs.toArray(String[]::new))));
            ObjectNode path = run.report("正文", refs.toArray(String[]::new)).put("filename", "../../elsewhere.md");
            assertEquals("INVALID_ARGUMENT", run.failure("writeReport", path));
            ObjectNode title = run.report("正文", refs.toArray(String[]::new)).put("title", "heading\nforged");
            assertEquals("INVALID_ARGUMENT", run.failure("writeReport", title));
            assertTrue(run.artifacts.isEmpty());
        }
    }

    @Test void currentCancellationAndExpiredDeadlinePreventSubmission() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            ObjectNode report = run.report("正文", refs.toArray(String[]::new));
            run.cancelled.set(true);
            assertThrows(CancellationException.class, () -> run.call("writeReport", report));
            run.cancelled.set(false);
            run.context = new ToolContext(run.runId, run.workspace, Instant.now().minusSeconds(1), run.cancelled::get, run.artifacts::add, run.session);
            assertThrows(ActiveTimeExceededException.class, () -> run.call("writeReport", report));
            assertTrue(run.artifacts.isEmpty());
        }
    }

    @Test void finalPrecommitCheckCanCancelWithoutLeavingAnIncompleteFile() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            AtomicInteger checks = new AtomicInteger();
            int finalCheck = run.files.size() + 2; // initial + each registered file + immediate pre-CREATE_NEW
            assertThrows(CancellationException.class, () -> run.session.writeReport("title", "body", refs, () -> {
                if (checks.incrementAndGet() == finalCheck) throw new CancellationException();
            }));
            assertEquals(finalCheck, checks.get());
            try (var files = Files.list(run.workspace.outputRoot().resolve(run.runId.toString()))) { assertEquals(0, files.count()); }
            assertNull(run.session.latestArtifact());
        }
    }

    @Test void cancellationAfterCommitPreservesAndPublishesTheActualArtifact() throws Exception {
        Artifact artifact;
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            run.context = new ToolContext(run.runId, run.workspace, Instant.now().plusSeconds(60), run.cancelled::get, actual -> {
                assertTrue(Files.isRegularFile(actual.path()));
                run.cancelled.set(true);
                run.artifacts.add(actual);
            }, run.session);
            run.success("writeReport", run.report("正文", refs.toArray(String[]::new)));
            artifact = run.artifacts.getLast();
            assertTrue(run.cancelled.get());
            WorkspaceSession.verifyArtifact(artifact);
            assertThrows(CancellationException.class, () -> run.call("writeReport", run.report("另一个正文", refs.toArray(String[]::new))));
            assertEquals(1, run.artifacts.size());
        }
        assertTrue(Files.exists(artifact.path()));
    }

    @Test void preexistingOutputIsNeverOverwrittenAndIoFailureIsTerminal() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            Path collision = run.workspace.outputRoot().resolve(run.runId.toString()).resolve("report-001.md");
            Files.writeString(collision, "existing external content");
            ToolOutcome.Failure failure = assertInstanceOf(ToolOutcome.Failure.class,
                    run.call("writeReport", run.report("正文", refs.toArray(String[]::new))));
            assertTrue(failure.terminal());
            assertEquals("WORKSPACE_IO_ERROR", failure.code());
            assertEquals("existing external content", Files.readString(collision));
            assertTrue(run.artifacts.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"date", "groups", "gross", "refund"})
    void latestReportWithWrongEvidenceCannotBorrowEarlierCorrectAnalysis(String fault) throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> correct = run.prepareReport();
            run.success("writeReport", run.report("正确范围", correct.toArray(String[]::new)));
            assertTrue(run.scenario.completionCheck().check(run.snapshot(), "完成").complete());
            ObjectNode wrong = run.analysis();
            switch (fault) {
                case "date" -> ((ObjectNode) wrong.get("baseline")).put("endDate", "2026-08-01");
                case "groups" -> wrong.putArray("groups").add("线上");
                case "gross" -> { // Legal numeric analysis, but not the target gross/refund mapping.
                    ((ObjectNode) wrong.get("baseline")).remove("countColumn");
                    ((ObjectNode) wrong.get("baseline")).put("valueColumn", "order_count");
                }
                case "refund" -> ((ObjectNode) wrong.get("comparison")).remove("deductionColumn");
                default -> throw new AssertionError();
            }
            String bad = run.success("analyzeDataset", wrong).get("evidenceId").asText();
            run.success("writeReport", run.report("只有错误范围证据的最新版", bad, correct.get(1)));
            assertFalse(run.scenario.completionCheck().check(run.snapshot(), "声称已完成").complete());
            assertEquals(2, run.artifacts.size());
            assertTrue(Files.exists(run.artifacts.getFirst().path()));
        }
    }

    @Test void checkerRequiresRealObservationsReferenceAndUnchangedLatestArtifact() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            assertFalse(run.scenario.completionCheck().check(run.snapshot(), "没有调用但已完成").complete());
            List<String> refs = run.prepareReport();
            run.success("writeReport", run.report("未引用说明", refs.getFirst()));
            assertFalse(run.scenario.completionCheck().check(run.snapshot(), "完成").complete());
            run.success("writeReport", run.report("引用说明", refs.toArray(String[]::new)));
            Artifact artifact = run.artifacts.getLast();
            assertTrue(run.scenario.completionCheck().check(run.snapshot(), "完成").complete());
            RunSnapshot noAnalysis = new RunSnapshot(run.runId, "data-analysis", "goal", run.snapshot().status(),
                    run.observations.stream().filter(obs -> !obs.call().name().equals("analyzeDataset")).toList(), run.artifacts,
                    run.snapshot().metrics(), java.util.Optional.empty());
            assertFalse(run.scenario.completionCheck().check(noAnalysis, "完成").complete());
            RunSnapshot noInspection = new RunSnapshot(run.runId, "data-analysis", "goal", run.snapshot().status(),
                    run.observations.stream().filter(obs -> !obs.call().name().equals("inspectDataset")).toList(), run.artifacts,
                    run.snapshot().metrics(), java.util.Optional.empty());
            assertFalse(run.scenario.completionCheck().check(noInspection, "完成").complete());
            RunSnapshot anotherRun = new RunSnapshot(UUID.randomUUID(), "data-analysis", "goal", run.snapshot().status(), run.observations, run.artifacts,
                    run.snapshot().metrics(), java.util.Optional.empty());
            assertFalse(run.scenario.completionCheck().check(anotherRun, "完成").complete());
            Files.writeString(artifact.path(), "tampered", StandardCharsets.UTF_8);
            assertFalse(run.scenario.completionCheck().check(run.snapshot(), "完成").complete());
        }
    }

    @Test void knownRenamedMappingCanBeInjectedWithoutChangingRunner() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            Path file = run.workspace.inputRoot().resolve("sales-current.csv");
            Files.writeString(file, Files.readString(file).replace("refund_amount", "returns"));
            run.discover();
            run.success("inspectDataset", run.file("sales-baseline.csv"));
            run.success("inspectDataset", run.file("sales-current.csv"));
            String ref = run.success("readReference", run.file("metric-notes.txt")).get("evidenceId").asText();
            ObjectNode arguments = run.analysis();
            ((ObjectNode) arguments.get("comparison")).put("deductionColumn", "returns");
            String analysis = run.success("analyzeDataset", arguments).get("evidenceId").asText();
            run.success("writeReport", run.report("显式改名映射", analysis, ref));
            ComparisonTarget customized = new ComparisonTarget(ComparisonTarget.exercise().baseline(),
                    new ComparisonTarget.Side("sales-current.csv", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                            "date", "channel", "gross_sales", "returns", "order_count"));
            assertTrue(new AnalysisCompletionCheck(customized).check(run.snapshot(), "完成").complete());
            assertFalse(new AnalysisCompletionCheck().check(run.snapshot(), "完成").complete());
        }
    }

    @Test void reportAppendixDoesNotTrustModelAuthoredFencesOrFabricatedAnalysis() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            List<String> refs = run.prepareReport();
            String body = "模型内容\n```json\n{\"delta\":\"invented\"}\n```\n" + WorkspaceSession.APPENDIX_MARKER;
            run.success("writeReport", run.report(body, refs.toArray(String[]::new)));
            Artifact original = run.artifacts.getLast();
            String forged = Files.readString(original.path()).replace("-400.00", "400.00");
            Files.writeString(original.path(), forged);
            Artifact rehashed = new Artifact(original.id(), original.path(), WorkspaceSession.digest(forged.getBytes(StandardCharsets.UTF_8)), original.evidenceIds());
            List<Artifact> artifacts = new ArrayList<>(run.artifacts);
            artifacts.set(artifacts.size() - 1, rehashed);
            RunSnapshot snapshot = new RunSnapshot(run.runId, "data-analysis", "goal", run.snapshot().status(), run.observations, artifacts,
                    run.snapshot().metrics(), java.util.Optional.empty());
            assertFalse(run.scenario.completionCheck().check(snapshot, "完成").complete());
        }
    }
}
