package com.agent.agentcorelab.agent.check;

import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.plan.TaskPlan;
import com.agent.agentcorelab.agent.plan.TaskStep;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceSession;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contract-driven completion check decoupling deliverable verification from hardcoded tool names and file names.
 * <p>
 * Evaluates completion through a 5-tier pipeline:
 * <ol>
 *   <li>Clarification & final answer presence (TEXT profile);</li>
 *   <li>Logical deliverable existence, freshness, and physical integrity (ARTIFACT profile);</li>
 *   <li>Factual evidence presence and non-hallucinated citations (EVIDENCE profile);</li>
 *   <li>Action execution safety and receipts with bounded repair constraints (ACTION profile);</li>
 *   <li>Plan completion consistency ({@link TaskPlan#isSatisfied()});</li>
 *   <li>Optional semantic evaluation ({@link CompletionJudge}), with hard deterministic precedence.</li>
 * </ol>
 */
public final class ContractDrivenCompletionCheck implements CompletionCheck {

    public static final String BOUNDED_REPAIR_GUARDRAIL =
            " [BOUNDED REPAIR GUARDRAIL: Do not automatically re-execute irreversible or financial action operations (e.g. payments, refunds, or mutations) to prevent duplicate execution; repair only text, read-only queries, or report deliverables.]";

    private static final Pattern CITE_INDEX_PATTERN = Pattern.compile("\\[[eE](\\d+)\\]");
    private static final Pattern CITE_UUID_PATTERN = Pattern.compile("e_[0-9a-fA-F\\-]{36}");

    private final CompletionRequirements requirements;

    public ContractDrivenCompletionCheck(CompletionRequirements requirements) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    public ContractDrivenCompletionCheck() {
        this(CompletionRequirements.textOnly());
    }

    public static ContractDrivenCompletionCheck of(CompletionRequirements requirements) {
        return new ContractDrivenCompletionCheck(requirements);
    }

    public CompletionRequirements requirements() {
        return requirements;
    }

    @Override
    public Result check(RunSnapshot run, String finalAnswer) {
        if (run == null) {
            return incompleteWithGuardrail("RUN_NULL: Run snapshot cannot be null");
        }

        // Clarification / sub-dialogue detection
        if (run.pendingQuestion().isPresent()) {
            return Result.intermediateExplanation(run.pendingQuestion().get().question());
        }

        // Layer 1: TEXT Profile & Basic Quality
        if (requirements.profiles().contains(CompletionProfile.TEXT)) {
            if (finalAnswer == null || finalAnswer.trim().isEmpty()) {
                return incompleteWithGuardrail("Final answer is required and cannot be empty.");
            }
        }

        // Layer 2: ARTIFACT Profile & Logical Deliverables
        if (requirements.profiles().contains(CompletionProfile.ARTIFACT)) {
            Result artifactResult = verifyDeliverables(run);
            if (!artifactResult.complete()) {
                return artifactResult;
            }
        }

        // Layer 3: EVIDENCE Profile & Citation Validation
        if (requirements.profiles().contains(CompletionProfile.EVIDENCE)) {
            Result evidenceResult = verifyEvidence(run, finalAnswer);
            if (!evidenceResult.complete()) {
                return evidenceResult;
            }
        }

        // Layer 4: ACTION Profile & Safety / Receipt Check
        if (requirements.profiles().contains(CompletionProfile.ACTION)) {
            Result actionResult = verifyActions(run);
            if (!actionResult.complete()) {
                return actionResult;
            }
        }

        // Layer 5: TaskPlan Consistency Check
        if (run.taskPlan().isPresent()) {
            TaskPlan plan = run.taskPlan().get();
            if (!plan.isSatisfied()) {
                long uncompleted = plan.steps().stream()
                        .filter(s -> s.status() == TaskStep.Status.PENDING
                                || s.status() == TaskStep.Status.ACTIVE
                                || s.status() == TaskStep.Status.BLOCKED)
                        .count();
                if (uncompleted > 0) {
                    return incompleteWithGuardrail("PLAN_INCONSISTENT: Task plan revision " + plan.planRevision()
                            + " has " + uncompleted + " uncompleted steps (PENDING, ACTIVE, or BLOCKED).");
                }
                boolean hasDone = plan.steps().stream().anyMatch(s -> s.status() == TaskStep.Status.DONE);
                if (!hasDone) {
                    return incompleteWithGuardrail("PLAN_INCONSISTENT: Task plan revision " + plan.planRevision()
                            + " has no steps marked DONE.");
                }
            }
        }

        // Layer 6: Semantic CompletionJudge (Optional - cannot override deterministic failures)
        if (requirements.judge().isPresent()) {
            CompletionJudge judge = requirements.judge().get();
            CompletionJudge.JudgeDecision decision = judge.evaluate(run, finalAnswer, requirements);
            if (decision.status() == CompletionJudge.JudgeDecision.Status.FAIL) {
                return incompleteWithGuardrail("SEMANTIC_CRITERIA_FAILED: " + decision.feedback());
            }
        }

        return Result.satisfied();
    }

    private Result verifyDeliverables(RunSnapshot run) {
        List<DeliverableRequirement> deliverables = requirements.deliverables();
        if (deliverables.isEmpty()) {
            if (run.artifacts().isEmpty()) {
                return incompleteWithGuardrail("MISSING_DELIVERABLE: At least one artifact deliverable is required in the workspace.");
            }
            for (Artifact artifact : run.artifacts()) {
                try {
                    WorkspaceSession.verifyArtifact(artifact);
                } catch (Exception e) {
                    return incompleteWithGuardrail("INVALID_ARTIFACT: Artifact '" + artifact.id()
                            + "' failed verification: " + e.getMessage());
                }
            }
            return Result.satisfied();
        }

        Set<String> matchedArtifactIds = new HashSet<>();
        for (DeliverableRequirement req : deliverables) {
            if (!req.required()) {
                continue;
            }
            Artifact matched = null;
            for (Artifact a : run.artifacts()) {
                if (!matchedArtifactIds.contains(a.id()) && req.matches(a)) {
                    matched = a;
                    break;
                }
            }
            if (matched == null) {
                return incompleteWithGuardrail("MISSING_DELIVERABLE: Required deliverable '" + req.id()
                        + "' (" + req.role() + ": " + req.description() + ") is missing in workspace session.");
            }
            matchedArtifactIds.add(matched.id());

            if (matched.goalVersion() < requirements.goalVersion()) {
                return incompleteWithGuardrail("STALE_GOAL: Deliverable '" + req.id() + "' (artifact '" + matched.id()
                        + "') was produced for goalVersion " + matched.goalVersion()
                        + ", but required goalVersion is " + requirements.goalVersion() + ". Please regenerate.");
            }

            try {
                WorkspaceSession.verifyArtifact(matched);
            } catch (Exception e) {
                return incompleteWithGuardrail("INVALID_ARTIFACT: Deliverable '" + req.id() + "' (artifact '" + matched.id()
                        + "') failed physical integrity check: " + e.getMessage());
            }

            if (req.minBytes() > 0) {
                try {
                    long size = Files.size(matched.path());
                    if (size < req.minBytes()) {
                        return incompleteWithGuardrail("EMPTY_DELIVERABLE: Deliverable '" + req.id() + "' (artifact '" + matched.id()
                                + "') size (" + size + " bytes) is smaller than required minimum (" + req.minBytes() + " bytes).");
                    }
                } catch (IOException e) {
                    return incompleteWithGuardrail("INVALID_ARTIFACT: Deliverable '" + req.id() + "' path could not be read: " + e.getMessage());
                }
            }
        }
        return Result.satisfied();
    }

    private Result verifyEvidence(RunSnapshot run, String finalAnswer) {
        EvidenceRequirement evReq = requirements.evidenceRequirement();
        List<RunSnapshot.Observation> successful = run.observations().stream()
                .filter(o -> o.outcome() instanceof ToolOutcome.Success)
                .toList();

        if (successful.size() < evReq.minObservations()) {
            return incompleteWithGuardrail("INSUFFICIENT_EVIDENCE: Required at least " + evReq.minObservations()
                    + " successful tool observations, but found " + successful.size() + ".");
        }

        if (evReq.mustCiteInAnswer()) {
            String answer = finalAnswer != null ? finalAnswer : "";
            Matcher indexMatcher = CITE_INDEX_PATTERN.matcher(answer);
            Matcher uuidMatcher = CITE_UUID_PATTERN.matcher(answer);

            boolean hasCitation = false;
            while (indexMatcher.find()) {
                hasCitation = true;
                int index = Integer.parseInt(indexMatcher.group(1));
                if (index < 1 || index > successful.size()) {
                    return incompleteWithGuardrail("INVALID_EVIDENCE_CITATION: Cited evidence [E" + index
                            + "] exceeds available observation count (" + successful.size() + ").");
                }
            }

            if (!hasCitation && uuidMatcher.find()) {
                hasCitation = true;
            }

            if (!hasCitation) {
                return incompleteWithGuardrail("MISSING_EVIDENCE_CITATION: Final answer must cite supporting evidence using references such as [E1], [E2].");
            }
        }

        return Result.satisfied();
    }

    private Result verifyActions(RunSnapshot run) {
        ActionRequirement actReq = requirements.actionRequirement();

        if (actReq.requireSuccessfulExecution()) {
            for (RunSnapshot.Observation obs : run.observations()) {
                if (obs.outcome() instanceof ToolOutcome.Failure fail) {
                    return incompleteWithGuardrail("ACTION_FAILED: Tool '" + obs.call().name() + "' failed: " + fail.code() + ": " + fail.message());
                }
            }
        }

        if (actReq.requireReceipt()) {
            boolean hasReceipt = false;
            for (RunSnapshot.Observation obs : run.observations()) {
                if (obs.outcome() instanceof ToolOutcome.Success succ) {
                    String out = succ.outputJson();
                    if (out != null && !out.isBlank()) {
                        hasReceipt = true;
                        break;
                    }
                }
            }
            if (!hasReceipt && !run.observations().isEmpty()) {
                return incompleteWithGuardrail("ACTION_RECEIPT_MISSING: Required action receipt was not produced in tool outputs.");
            }
        }

        return Result.satisfied();
    }

    private Result incompleteWithGuardrail(String feedback) {
        if (requirements.profiles().contains(CompletionProfile.ACTION)
                && requirements.actionRequirement().disallowDestructiveRepair()) {
            return Result.incomplete(feedback + BOUNDED_REPAIR_GUARDRAIL);
        }
        return Result.incomplete(feedback);
    }
}
