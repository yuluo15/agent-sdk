package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.tool.ClarificationTool;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the recommended multi-Agent composition pattern at the application layer.
 * <p>
 * Architectural Guiding Principle:
 * The SDK core remains a pure, focused single-Agent execution engine (managing the loop,
 * workspace, tools, budgets, and approval states for an individual Agent).
 * Multi-Agent handoff, pipeline coordination, and waiting state routing are naturally
 * implemented in the application layer (e.g., in a Spring service) without bloating the SDK.
 */
class AgentCompositionPatternTests {

    @TempDir
    Path tempDir;

    private Workspace workspace;
    private RunLimits limits;

    @BeforeEach
    void setUp() throws IOException {
        Path inputDir = tempDir.resolve("input");
        Path outputDir = tempDir.resolve("output");
        Path traceDir = tempDir.resolve("traces");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.createDirectories(traceDir);
        workspace = new Workspace(inputDir, outputDir, traceDir);
        limits = new RunLimits(10, 20, Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    // =========================================================================
    // Domain Tools & Mock Sessions
    // =========================================================================

    private Tool createLogAnalysisTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("analyzeLogs", "Query system error logs", "{\"type\":\"object\"}", RiskLevel.READ_ONLY);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                return new ToolOutcome.Success("{\"errorFound\":true,\"rootCause\":\"OOM_IN_POD_AUTH\",\"affectedComponent\":\"auth-service\"}");
            }
        };
    }

    private Tool createHighRiskRemediationTool(AtomicInteger rebootCounter) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("rebootService", "Restart affected service pod",
                        "{\"type\":\"object\",\"properties\":{\"service\":{\"type\":\"string\"}},\"required\":[\"service\"]}",
                        RiskLevel.HIGH_RISK);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                rebootCounter.incrementAndGet();
                return new ToolOutcome.Success("{\"status\":\"RESTARTED\",\"service\":\"auth-service\"}");
            }
        };
    }

    // =========================================================================
    // Application-Level Multi-Agent Orchestrator (Demonstration)
    // =========================================================================

    /**
     * Example of an application service coordinating two independent AgentRunners.
     * Stage 1: Triage / Diagnostics Agent (Read-only discovery & root cause analysis)
     * Stage 2: Remediation Agent (Human-in-the-loop controlled remediation execution)
     */
    static class IncidentOrchestrator {
        private final AgentRunner triageRunner;
        private final AgentSpec triageSpec;
        private final AgentRunner remediationRunner;
        private final AgentSpec remediationSpec;
        private final Workspace workspace;

        private UUID activeTriageRunId;
        private UUID activeRemediationRunId;

        IncidentOrchestrator(AgentRunner triageRunner, AgentSpec triageSpec,
                             AgentRunner remediationRunner, AgentSpec remediationSpec,
                             Workspace workspace) {
            this.triageRunner = triageRunner;
            this.triageSpec = triageSpec;
            this.remediationRunner = remediationRunner;
            this.remediationSpec = remediationSpec;
            this.workspace = workspace;
        }

        public OrchestrationResult executePipeline(String incidentReport) {
            // Step 1: Execute Triage Agent
            RunResult triageResult = triageRunner.run(triageSpec, new RunRequest(incidentReport, workspace));
            this.activeTriageRunId = triageResult.snapshot().runId();

            // Handle waiting state in Stage 1 (e.g. clarification needed)
            if (triageResult.snapshot().status() == RunStatus.WAITING_INPUT) {
                return new OrchestrationResult(Stage.TRIAGE, triageResult, null);
            }

            // Circuit break on failure: Never proceed to remediation if triage failed or cancelled
            if (triageResult.snapshot().status() != RunStatus.COMPLETED) {
                return new OrchestrationResult(Stage.TRIAGE, triageResult, null);
            }

            // Step 2: Context Handoff -> Construct goal for Remediation Agent
            String diagnosisSummary = triageResult.message();
            String remediationGoal = "Execute remediation plan based on verified diagnosis: " + diagnosisSummary;

            RunResult remediationResult = remediationRunner.run(remediationSpec, new RunRequest(remediationGoal, workspace));
            this.activeRemediationRunId = remediationResult.snapshot().runId();

            return new OrchestrationResult(Stage.REMEDIATION, triageResult, remediationResult);
        }

        public OrchestrationResult resumeTriageClarification(String answer) {
            RunResult resumedTriage = triageRunner.resume(activeTriageRunId, answer);
            if (resumedTriage.snapshot().status() != RunStatus.COMPLETED) {
                return new OrchestrationResult(Stage.TRIAGE, resumedTriage, null);
            }
            // Once triage completes, automatically advance to remediation
            String remediationGoal = "Execute remediation plan based on verified diagnosis: " + resumedTriage.message();
            RunResult remediationResult = remediationRunner.run(remediationSpec, new RunRequest(remediationGoal, workspace));
            this.activeRemediationRunId = remediationResult.snapshot().runId();
            return new OrchestrationResult(Stage.REMEDIATION, resumedTriage, remediationResult);
        }

        public OrchestrationResult approveRemediation(String approvalId) {
            RunResult approvedResult = remediationRunner.approve(activeRemediationRunId, approvalId);
            return new OrchestrationResult(Stage.REMEDIATION, null, approvedResult);
        }

        enum Stage { TRIAGE, REMEDIATION }
        record OrchestrationResult(Stage currentStage, RunResult triageResult, RunResult remediationResult) {}
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void testTwoAgentSequentialHandoffCompletesSuccessfully() {
        AtomicInteger rebootCounter = new AtomicInteger();
        SimpleScriptSession triageSession = new SimpleScriptSession();
        SimpleScriptSession remediationSession = new SimpleScriptSession();

        Tool logTool = createLogAnalysisTool();
        Tool rebootTool = createHighRiskRemediationTool(rebootCounter);

        AgentRunner triageRunner = new DefaultAgentRunner((inst, g, defs) -> triageSession,
                new ToolCatalog(List.of(logTool, new ClarificationTool())), event -> {});
        AgentRunner remediationRunner = new DefaultAgentRunner((inst, g, defs) -> remediationSession,
                new ToolCatalog(List.of(rebootTool, new ClarificationTool())), event -> {});

        AgentSpec triageSpec = new AgentSpec("triage-agent", "Perform diagnostics",
                Set.of("analyzeLogs"), limits, (s, a) -> CompletionCheck.Result.satisfied());
        AgentSpec remediationSpec = new AgentSpec("remediation-agent", "Perform safe remediation",
                Set.of("rebootService"), limits, (s, a) -> CompletionCheck.Result.satisfied());

        IncidentOrchestrator orchestrator = new IncidentOrchestrator(triageRunner, triageSpec,
                remediationRunner, remediationSpec, workspace);

        // Stage 1 script: Calls analyzeLogs, then outputs diagnosis
        triageSession.enqueue(new ModelResponse("", List.of(new ToolCall("c1", "analyzeLogs", "{}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        triageSession.enqueue(new ModelResponse("Diagnosis confirmed: OOM_IN_POD_AUTH in auth-service.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Stage 2 script: Remediation completes immediately with an informational summary
        remediationSession.enqueue(new ModelResponse("Remediation completed: Alert dispatched and ticket updated.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        IncidentOrchestrator.OrchestrationResult result = orchestrator.executePipeline("Auth login requests failing with 500");

        assertEquals(IncidentOrchestrator.Stage.REMEDIATION, result.currentStage());
        assertEquals(RunStatus.COMPLETED, result.triageResult().snapshot().status());
        assertEquals(RunStatus.COMPLETED, result.remediationResult().snapshot().status());
        assertTrue(result.triageResult().message().contains("OOM_IN_POD_AUTH"));
        assertTrue(result.remediationResult().message().contains("Remediation completed"));
    }

    @Test
    void testWaitingApprovalPassthroughAcrossTwoAgents() {
        AtomicInteger rebootCounter = new AtomicInteger();
        SimpleScriptSession triageSession = new SimpleScriptSession();
        SimpleScriptSession remediationSession = new SimpleScriptSession();

        Tool logTool = createLogAnalysisTool();
        Tool rebootTool = createHighRiskRemediationTool(rebootCounter);

        AgentRunner triageRunner = new DefaultAgentRunner((inst, g, defs) -> triageSession,
                new ToolCatalog(List.of(logTool)), event -> {});
        AgentRunner remediationRunner = new DefaultAgentRunner((inst, g, defs) -> remediationSession,
                new ToolCatalog(List.of(rebootTool)), event -> {});

        AgentSpec triageSpec = new AgentSpec("triage-agent", "Diagnostics",
                Set.of("analyzeLogs"), limits, (s, a) -> CompletionCheck.Result.satisfied());
        AgentSpec remediationSpec = new AgentSpec("remediation-agent", "Remediation",
                Set.of("rebootService"), limits, (s, a) -> CompletionCheck.Result.satisfied());

        IncidentOrchestrator orchestrator = new IncidentOrchestrator(triageRunner, triageSpec,
                remediationRunner, remediationSpec, workspace);

        // Triage finishes cleanly
        triageSession.enqueue(new ModelResponse("Root cause: auth-service deadlocked.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Remediation triggers high-risk rebootService
        remediationSession.enqueue(new ModelResponse("",
                List.of(new ToolCall("r1", "rebootService", "{\"service\":\"auth-service\"}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        remediationSession.enqueue(new ModelResponse("auth-service successfully rebooted and healthy.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Run pipeline -> Stage 2 stops at WAITING_APPROVAL
        IncidentOrchestrator.OrchestrationResult step1 = orchestrator.executePipeline("Auth deadlock alert");

        assertEquals(IncidentOrchestrator.Stage.REMEDIATION, step1.currentStage());
        assertEquals(RunStatus.COMPLETED, step1.triageResult().snapshot().status());
        assertEquals(RunStatus.WAITING_APPROVAL, step1.remediationResult().snapshot().status());
        assertEquals(0, rebootCounter.get(), "High-risk tool must not run before approval");

        String approvalId = step1.remediationResult().snapshot().pendingApproval().orElseThrow().approvalId();

        // User approves the operation via orchestrator
        IncidentOrchestrator.OrchestrationResult step2 = orchestrator.approveRemediation(approvalId);

        assertEquals(RunStatus.COMPLETED, step2.remediationResult().snapshot().status());
        assertEquals(1, rebootCounter.get(), "High-risk tool executes after approval");
        assertTrue(step2.remediationResult().message().contains("successfully rebooted"));
    }

    @Test
    void testClarificationInputPassthroughAndResume() {
        SimpleScriptSession triageSession = new SimpleScriptSession();
        SimpleScriptSession remediationSession = new SimpleScriptSession();

        Tool logTool = createLogAnalysisTool();
        Tool clarification = new ClarificationTool();

        AgentRunner triageRunner = new DefaultAgentRunner((inst, g, defs) -> triageSession,
                new ToolCatalog(List.of(logTool, clarification)), event -> {});
        AgentRunner remediationRunner = new DefaultAgentRunner((inst, g, defs) -> remediationSession,
                new ToolCatalog(List.of(logTool)), event -> {});

        AgentSpec triageSpec = new AgentSpec("triage-agent", "Diagnostics",
                Set.of("analyzeLogs", ClarificationTool.NAME), limits, (s, a) -> CompletionCheck.Result.satisfied());
        AgentSpec remediationSpec = new AgentSpec("remediation-agent", "Remediation",
                Set.of("analyzeLogs"), limits, (s, a) -> CompletionCheck.Result.satisfied());

        IncidentOrchestrator orchestrator = new IncidentOrchestrator(triageRunner, triageSpec,
                remediationRunner, remediationSpec, workspace);

        // Turn 1: Triage asks for cluster environment
        triageSession.enqueue(new ModelResponse("",
                List.of(new ToolCall("q1", ClarificationTool.NAME, "{\"question\":\"Which cluster region?\"}")),
                ModelResponse.FinishReason.TOOL_CALLS, Optional.empty()));
        // Turn 2: After user answers, triage completes
        triageSession.enqueue(new ModelResponse("Diagnosed region us-east-1: auth-service CPU spike.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Stage 2 completion
        remediationSession.enqueue(new ModelResponse("Remediation scaled up auth pods.", List.of(),
                ModelResponse.FinishReason.STOP, Optional.empty()));

        // Pipeline starts -> pauses in Stage 1 (WAITING_INPUT)
        IncidentOrchestrator.OrchestrationResult step1 = orchestrator.executePipeline("Cluster performance degraded");
        assertEquals(IncidentOrchestrator.Stage.TRIAGE, step1.currentStage());
        assertEquals(RunStatus.WAITING_INPUT, step1.triageResult().snapshot().status());
        assertNull(step1.remediationResult());

        // User answers the clarification -> Pipeline resumes Stage 1, then advances to Stage 2
        IncidentOrchestrator.OrchestrationResult step2 = orchestrator.resumeTriageClarification("us-east-1");
        assertEquals(IncidentOrchestrator.Stage.REMEDIATION, step2.currentStage());
        assertEquals(RunStatus.COMPLETED, step2.triageResult().snapshot().status());
        assertEquals(RunStatus.COMPLETED, step2.remediationResult().snapshot().status());
        assertTrue(step2.remediationResult().message().contains("scaled up auth pods"));
    }

    @Test
    void testFailureIsolationPreventsDownstreamStageExecution() {
        SimpleScriptSession triageSession = new SimpleScriptSession();
        SimpleScriptSession remediationSession = new SimpleScriptSession();

        AgentRunner triageRunner = new DefaultAgentRunner((inst, g, defs) -> triageSession,
                new ToolCatalog(List.of()), event -> {});
        AgentRunner remediationRunner = new DefaultAgentRunner((inst, g, defs) -> remediationSession,
                new ToolCatalog(List.of()), event -> {});

        AgentSpec triageSpec = new AgentSpec("triage-agent", "Diagnostics",
                Set.of(), limits, (s, a) -> CompletionCheck.Result.satisfied());
        AgentSpec remediationSpec = new AgentSpec("remediation-agent", "Remediation",
                Set.of(), limits, (s, a) -> CompletionCheck.Result.satisfied());

        IncidentOrchestrator orchestrator = new IncidentOrchestrator(triageRunner, triageSpec,
                remediationRunner, remediationSpec, workspace);

        // Triage encounters a protocol error / fails
        triageSession.enqueue(new ModelResponse("Bad response", List.of(),
                ModelResponse.FinishReason.ERROR, Optional.empty()));

        IncidentOrchestrator.OrchestrationResult result = orchestrator.executePipeline("Trigger triage error");

        assertEquals(IncidentOrchestrator.Stage.TRIAGE, result.currentStage());
        assertEquals(RunStatus.FAILED, result.triageResult().snapshot().status());
        assertNull(result.remediationResult(), "Downstream remediation must NOT be invoked when upstream triage fails");
    }

    // =========================================================================
    // Minimal Mock Session
    // =========================================================================

    private static final class SimpleScriptSession implements ModelSession {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();

        void enqueue(ModelResponse resp) {
            responses.add(resp);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            ModelResponse r = responses.poll();
            return r != null ? r : new ModelResponse("Default completion", List.of(), ModelResponse.FinishReason.STOP, Optional.empty());
        }

        @Override public void appendToolResult(String callId, ToolOutcome outcome) {}
        @Override public void appendUserMessage(String message) {}
        @Override public void appendRuntimeFeedback(String value) {}
        @Override public long estimatedInputTokens() { return 50; }
        @Override public void close() {}
    }
}
