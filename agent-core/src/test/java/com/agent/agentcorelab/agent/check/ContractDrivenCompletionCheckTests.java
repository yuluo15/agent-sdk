package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.plan.TaskPlan;
import com.agent.agentcorelab.agent.plan.TaskStep;
import com.agent.agentcorelab.agent.run.PendingQuestion;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractDrivenCompletionCheckTests {

    private RunSnapshot createSnapshot(
            List<RunSnapshot.Observation> observations,
            List<Artifact> artifacts,
            Optional<PendingQuestion> pendingQuestion,
            Optional<TaskPlan> taskPlan
    ) {
        return new RunSnapshot(
                UUID.randomUUID(),
                "test-agent",
                "Verify contract check",
                RunStatus.RUNNING,
                observations != null ? observations : List.of(),
                artifacts != null ? artifacts : List.of(),
                new RunMetrics(1, 0, Duration.ofMillis(500), Optional.empty()),
                pendingQuestion != null ? pendingQuestion : Optional.empty(),
                Optional.empty(),
                taskPlan != null ? taskPlan : Optional.empty()
        );
    }

    @Test
    void testTextOnlySatisfied() {
        var check = ContractDrivenCompletionCheck.of(CompletionRequirements.textOnly());
        var run = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.empty());

        var result = check.check(run, "Here is the comprehensive answer to your inquiry.");
        assertTrue(result.complete());
        assertEquals(CompletionCheck.Result.Status.COMPLETE, result.status());
    }

    @Test
    void testTextOnlyEmptyAnswer() {
        var check = ContractDrivenCompletionCheck.of(CompletionRequirements.textOnly());
        var run = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.empty());

        var result = check.check(run, "   ");
        assertFalse(result.complete());
        assertTrue(result.feedback().contains("Final answer is required"));
    }

    @Test
    void testIntermediateExplanationWhenPendingQuestion() {
        var check = ContractDrivenCompletionCheck.of(CompletionRequirements.textOnly());
        var pendingQ = Optional.of(new PendingQuestion("q1", "Which region should I analyze?", List.of("US", "EU")));
        var run = createSnapshot(List.of(), List.of(), pendingQ, Optional.empty());

        var result = check.check(run, "I need clarification on the region.");
        assertFalse(result.complete());
        assertEquals(CompletionCheck.Result.Status.INTERMEDIATE_EXPLANATION, result.status());
        assertEquals("Which region should I analyze?", result.feedback());
    }

    @Test
    void testLogicalDeliverableRoleMatching(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("executive-summary-001.md");
        byte[] content = "# Executive Summary\n\nSales increased by 15%.".getBytes(StandardCharsets.UTF_8);
        Files.write(reportFile, content);
        String digest = WorkspaceSession.digest(content);

        Artifact artifact = new Artifact("art-001", reportFile, digest, List.of(), 1);

        var requirements = CompletionRequirements.builder()
                .addDeliverable(DeliverableRequirement.report("analysis_report", "Logical Q3 analysis report"))
                .build();

        var check = ContractDrivenCompletionCheck.of(requirements);
        var run = createSnapshot(List.of(), List.of(artifact), Optional.empty(), Optional.empty());

        var result = check.check(run, "Task complete with report.");
        assertTrue(result.complete(), "Logical role REPORT should match executive-summary-001.md");
    }

    @Test
    void testDeliverablePhysicalIntegrityFailure(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("report.md");
        Files.writeString(reportFile, "Original content");
        String originalDigest = WorkspaceSession.digest("Original content".getBytes(StandardCharsets.UTF_8));

        Artifact artifact = new Artifact("rep-1", reportFile, originalDigest, List.of(), 1);

        // Tamper with the file so digest no longer matches
        Files.writeString(reportFile, "Tampered content after hashing!");

        var requirements = CompletionRequirements.builder()
                .addDeliverable(DeliverableRequirement.report("rep-1", "Report deliverable"))
                .build();
        var check = ContractDrivenCompletionCheck.of(requirements);
        var run = createSnapshot(List.of(), List.of(artifact), Optional.empty(), Optional.empty());

        var result = check.check(run, "Completed");
        assertFalse(result.complete());
        assertTrue(result.feedback().contains("INVALID_ARTIFACT"));
    }

    @Test
    void testDeliverableStaleGoalVersion(@TempDir Path tempDir) throws IOException {
        Path reportFile = tempDir.resolve("report.md");
        byte[] bytes = "Content".getBytes(StandardCharsets.UTF_8);
        Files.write(reportFile, bytes);
        String digest = WorkspaceSession.digest(bytes);

        // Produced for goalVersion 1
        Artifact artifact = new Artifact("rep-1", reportFile, digest, List.of(), 1);

        // Current requirement requires revised goalVersion 2
        var requirements = CompletionRequirements.builder()
                .goalVersion(2)
                .addDeliverable(DeliverableRequirement.report("rep-1", "Report deliverable"))
                .build();

        var check = ContractDrivenCompletionCheck.of(requirements);
        var run = createSnapshot(List.of(), List.of(artifact), Optional.empty(), Optional.empty());

        var result = check.check(run, "Completed");
        assertFalse(result.complete());
        assertTrue(result.feedback().contains("STALE_GOAL"));
    }

    @Test
    void testDeliverableMissing() {
        var requirements = CompletionRequirements.builder()
                .addDeliverable(DeliverableRequirement.dataset("data_export", "Export of records", ".csv"))
                .build();
        var check = ContractDrivenCompletionCheck.of(requirements);
        var run = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.empty());

        var result = check.check(run, "Done");
        assertFalse(result.complete());
        assertTrue(result.feedback().contains("MISSING_DELIVERABLE"));
    }

    @Test
    void testEvidenceMinimumObservations() {
        var requirements = CompletionRequirements.textWithEvidence(2, false);
        var check = ContractDrivenCompletionCheck.of(requirements);

        var call1 = new ToolCall("c1", "query_sales", "{}");
        var obs1 = new RunSnapshot.Observation(call1, new ToolOutcome.Success("{\"count\": 10}"));

        // Only 1 observation, requires 2
        var run1 = createSnapshot(List.of(obs1), List.of(), Optional.empty(), Optional.empty());
        var res1 = check.check(run1, "Here is the sales result.");
        assertFalse(res1.complete());
        assertTrue(res1.feedback().contains("INSUFFICIENT_EVIDENCE"));

        // Add 2nd observation
        var call2 = new ToolCall("c2", "query_returns", "{}");
        var obs2 = new RunSnapshot.Observation(call2, new ToolOutcome.Success("{\"returns\": 2}"));
        var run2 = createSnapshot(List.of(obs1, obs2), List.of(), Optional.empty(), Optional.empty());

        var res2 = check.check(run2, "Here is the sales result.");
        assertTrue(res2.complete());
    }

    @Test
    void testEvidenceCitationsMatching() {
        var requirements = CompletionRequirements.textWithEvidence(2, true);
        var check = ContractDrivenCompletionCheck.of(requirements);

        var obs1 = new RunSnapshot.Observation(new ToolCall("c1", "t1", "{}"), new ToolOutcome.Success("{\"a\": 1}"));
        var obs2 = new RunSnapshot.Observation(new ToolCall("c2", "t2", "{}"), new ToolOutcome.Success("{\"b\": 2}"));
        var run = createSnapshot(List.of(obs1, obs2), List.of(), Optional.empty(), Optional.empty());

        // Answer citing [E1] and [E2]
        var res = check.check(run, "According to [E1], the input was recorded, and [E2] confirmed the status.");
        assertTrue(res.complete());
    }

    @Test
    void testEvidenceCitationHallucinationDetected() {
        var requirements = CompletionRequirements.textWithEvidence(2, true);
        var check = ContractDrivenCompletionCheck.of(requirements);

        var obs1 = new RunSnapshot.Observation(new ToolCall("c1", "t1", "{}"), new ToolOutcome.Success("{\"a\": 1}"));
        var obs2 = new RunSnapshot.Observation(new ToolCall("c2", "t2", "{}"), new ToolOutcome.Success("{\"b\": 2}"));
        var run = createSnapshot(List.of(obs1, obs2), List.of(), Optional.empty(), Optional.empty());

        // Model hallucinates citing [E3] when only 2 observations exist
        var res = check.check(run, "According to [E3], the metric is 99%.");
        assertFalse(res.complete());
        assertTrue(res.feedback().contains("INVALID_EVIDENCE_CITATION"));
        assertTrue(res.feedback().contains("[E3] exceeds available observation count (2)"));
    }

    @Test
    void testEvidenceMissingCitationInAnswer() {
        var requirements = CompletionRequirements.textWithEvidence(1, true);
        var check = ContractDrivenCompletionCheck.of(requirements);

        var obs1 = new RunSnapshot.Observation(new ToolCall("c1", "t1", "{}"), new ToolOutcome.Success("{\"a\": 1}"));
        var run = createSnapshot(List.of(obs1), List.of(), Optional.empty(), Optional.empty());

        var res = check.check(run, "The answer is 42, but I did not cite any evidence.");
        assertFalse(res.complete());
        assertTrue(res.feedback().contains("MISSING_EVIDENCE_CITATION"));
    }

    @Test
    void testActionSuccessfulExecutionAndReceipt() {
        var requirements = CompletionRequirements.builder()
                .actionRequirement(ActionRequirement.strictAction(true))
                .build();
        var check = ContractDrivenCompletionCheck.of(requirements);

        var obs = new RunSnapshot.Observation(
                new ToolCall("c1", "execute_refund", "{\"amount\": 100}"),
                new ToolOutcome.Success("{\"receiptId\": \"rec_999\", \"status\": \"SUCCESS\"}")
        );
        var run = createSnapshot(List.of(obs), List.of(), Optional.empty(), Optional.empty());

        var res = check.check(run, "Refund processed successfully.");
        assertTrue(res.complete());
    }

    @Test
    void testActionFailureAndBoundedRepairGuardrail() {
        var requirements = CompletionRequirements.builder()
                .actionRequirement(ActionRequirement.strictAction(true))
                .build();
        var check = ContractDrivenCompletionCheck.of(requirements);

        var obs = new RunSnapshot.Observation(
                new ToolCall("c1", "execute_refund", "{\"amount\": 100}"),
                new ToolOutcome.Failure("NETWORK_TIMEOUT", "Connection dropped during payment", false)
        );
        var run = createSnapshot(List.of(obs), List.of(), Optional.empty(), Optional.empty());

        var res = check.check(run, "Payment attempt finished.");
        assertFalse(res.complete());
        assertTrue(res.feedback().contains("ACTION_FAILED"));
        assertTrue(res.feedback().contains("BOUNDED REPAIR GUARDRAIL"));
        assertTrue(res.feedback().contains("Do not automatically re-execute irreversible or financial action operations"));
    }

    @Test
    void testTaskPlanConsistency() {
        var check = ContractDrivenCompletionCheck.of(CompletionRequirements.textOnly());

        // 1. All DONE steps -> passes
        var plan1 = new TaskPlan(1, 1, "Completed plan", List.of(
                TaskStep.of("s1", "Query DB", TaskStep.Status.DONE, List.of("e1"), "Done"),
                TaskStep.of("s2", "Format results", TaskStep.Status.DONE, List.of("e2"), "Done")
        ));
        var run1 = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.of(plan1));
        assertTrue(check.check(run1, "Answer").complete());

        // 2. DONE step + DROPPED step with valid reason -> passes
        var plan2 = new TaskPlan(1, 1, "Adaptive plan", List.of(
                TaskStep.of("s1", "Query DB", TaskStep.Status.DONE, List.of("e1"), "Done"),
                TaskStep.of("s2", "External sync", TaskStep.Status.DROPPED, List.of(), "External system offline, skipped")
        ));
        var run2 = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.of(plan2));
        assertTrue(check.check(run2, "Answer").complete());

        // 3. Uncompleted ACTIVE step remains -> fails
        var plan3 = new TaskPlan(1, 1, "In-progress plan", List.of(
                TaskStep.of("s1", "Query DB", TaskStep.Status.DONE, List.of("e1"), "Done"),
                TaskStep.of("s2", "External sync", TaskStep.Status.ACTIVE)
        ));
        var run3 = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.of(plan3));
        var res3 = check.check(run3, "Answer");
        assertFalse(res3.complete());
        assertTrue(res3.feedback().contains("PLAN_INCONSISTENT"));

        // 4. Only DROPPED steps (no DONE) -> fails
        var plan4 = new TaskPlan(1, 1, "All dropped", List.of(
                TaskStep.of("s1", "Query DB", TaskStep.Status.DROPPED, List.of(), "Skipped")
        ));
        var run4 = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.of(plan4));
        var res4 = check.check(run4, "Answer");
        assertFalse(res4.complete());
        assertTrue(res4.feedback().contains("PLAN_INCONSISTENT"));
    }

    @Test
    void testCompletionJudgePrecedence() {
        AtomicBoolean judgeCalled = new AtomicBoolean(false);

        // Test 1: Deterministic check passes, Judge evaluates semantic tone
        CompletionJudge passingJudge = (run, answer, reqs) -> {
            judgeCalled.set(true);
            return CompletionJudge.JudgeDecision.pass("Prose quality is professional");
        };

        var reqs1 = CompletionRequirements.builder()
                .judge(passingJudge)
                .build();
        var check1 = ContractDrivenCompletionCheck.of(reqs1);
        var run1 = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.empty());

        var res1 = check1.check(run1, "Valid response text.");
        assertTrue(res1.complete());
        assertTrue(judgeCalled.get());

        // Test 2: Deterministic passes, Judge fails on subjective criteria
        CompletionJudge failingJudge = (run, answer, reqs) -> CompletionJudge.JudgeDecision.fail("Answer tone is too casual");
        var reqs2 = CompletionRequirements.builder().judge(failingJudge).build();
        var check2 = ContractDrivenCompletionCheck.of(reqs2);
        var res2 = check2.check(run1, "Hey dude whatever.");
        assertFalse(res2.complete());
        assertTrue(res2.feedback().contains("SEMANTIC_CRITERIA_FAILED: Answer tone is too casual"));

        // Test 3: Deterministic check fails (empty text) -> Judge is NEVER called (deterministic precedence)
        judgeCalled.set(false);
        var reqs3 = CompletionRequirements.builder().judge(passingJudge).build();
        var check3 = ContractDrivenCompletionCheck.of(reqs3);
        var res3 = check3.check(run1, "  ");
        assertFalse(res3.complete());
        assertFalse(judgeCalled.get(), "Judge must not be evaluated if deterministic checks fail");
    }

    @Test
    void testCompositeCompletionCheck() {
        CompletionCheck check1 = (run, ans) -> ans.contains("apple") ? CompletionCheck.Result.satisfied() : CompletionCheck.Result.incomplete("no apple");
        CompletionCheck check2 = (run, ans) -> ans.contains("banana") ? CompletionCheck.Result.satisfied() : CompletionCheck.Result.incomplete("no banana");

        var compositeAll = CompositeCompletionCheck.all(check1, check2);
        var compositeAny = CompositeCompletionCheck.any(check1, check2);

        var run = createSnapshot(List.of(), List.of(), Optional.empty(), Optional.empty());

        // "apple" only
        assertFalse(compositeAll.check(run, "I have an apple").complete());
        assertTrue(compositeAny.check(run, "I have an apple").complete());

        // "banana" only
        assertFalse(compositeAll.check(run, "I have a banana").complete());
        assertTrue(compositeAny.check(run, "I have a banana").complete());

        // Both
        assertTrue(compositeAll.check(run, "I have apple and banana").complete());
        assertTrue(compositeAny.check(run, "I have apple and banana").complete());

        // Neither
        assertFalse(compositeAll.check(run, "I have orange").complete());
        assertFalse(compositeAny.check(run, "I have orange").complete());
    }
}
