package com.agent.agentcorelab.bootstrap.metrics;

import com.agent.agentcorelab.trace.RunEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentMetricsRunEventListenerTests {

    private SimpleMeterRegistry registry;
    private AgentMetricsRunEventListener listener;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        listener = new AgentMetricsRunEventListener(registry);
    }

    @Test
    void activeRunsGaugeTracksLifecycle() {
        Gauge activeGauge = registry.find("agent.runs.active").gauge();
        assertNotNull(activeGauge);
        assertEquals(0.0, activeGauge.value());

        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_STARTED,
                "{\"agentId\":\"it-triage\",\"goal\":\"Diagnose server\"}"));
        assertEquals(1.0, activeGauge.value());
        assertEquals(1, listener.activeRuns());

        listener.onEvent(new RunEvent(2, runId, Instant.now(), RunEvent.Type.RUN_FINISHED,
                "{\"agentId\":\"it-triage\",\"status\":\"COMPLETED\",\"reason\":\"COMPLETED\",\"activeMillis\":1500}"));
        assertEquals(0.0, activeGauge.value());
        assertEquals(0, listener.activeRuns());
    }

    @Test
    void recordsSegmentedDurationsAndTokenCounters() {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_STARTED,
                "{\"agentId\":\"it-triage\"}"));

        listener.onEvent(new RunEvent(2, runId, Instant.now(), RunEvent.Type.MODEL_FINISHED,
                "{\"agentId\":\"it-triage\",\"request\":1}"));

        listener.onEvent(new RunEvent(3, runId, Instant.now(), RunEvent.Type.TOOL_FINISHED,
                "{\"agentId\":\"it-triage\",\"tool\":\"lookupTicket\"}"));

        String finishedSummary = """
                {
                  "agentId": "it-triage",
                  "status": "COMPLETED",
                  "reason": "COMPLETED",
                  "activeMillis": 2400,
                  "modelMillis": 1600,
                  "toolMillis": 650,
                  "waitingMillis": 150,
                  "usage": {
                    "inputTokens": 1200,
                    "outputTokens": 350
                  }
                }
                """;
        listener.onEvent(new RunEvent(4, runId, Instant.now(), RunEvent.Type.RUN_FINISHED, finishedSummary));

        // Timers
        Timer activeTimer = registry.find("agent.run.active.duration")
                .tag("agent_id", "it-triage")
                .tag("status", "COMPLETED")
                .timer();
        assertNotNull(activeTimer);
        assertEquals(1, activeTimer.count());
        assertEquals(2400.0, activeTimer.totalTime(TimeUnit.MILLISECONDS), 1.0);

        Timer modelTimer = registry.find("agent.run.model.duration")
                .tag("agent_id", "it-triage")
                .timer();
        assertNotNull(modelTimer);
        assertEquals(1600.0, modelTimer.totalTime(TimeUnit.MILLISECONDS), 1.0);

        Timer toolTimer = registry.find("agent.run.tool.duration")
                .tag("agent_id", "it-triage")
                .timer();
        assertNotNull(toolTimer);
        assertEquals(650.0, toolTimer.totalTime(TimeUnit.MILLISECONDS), 1.0);

        Timer waitingTimer = registry.find("agent.run.waiting.duration")
                .tag("agent_id", "it-triage")
                .timer();
        assertNotNull(waitingTimer);
        assertEquals(150.0, waitingTimer.totalTime(TimeUnit.MILLISECONDS), 1.0);

        // Runs total counter
        Counter runsCounter = registry.find("agent.runs.total")
                .tag("agent_id", "it-triage")
                .tag("status", "COMPLETED")
                .tag("reason", "COMPLETED")
                .counter();
        assertNotNull(runsCounter);
        assertEquals(1.0, runsCounter.count());

        // Token counters
        Counter inputTokens = registry.find("agent.run.tokens.total")
                .tag("agent_id", "it-triage")
                .tag("type", "input")
                .counter();
        assertNotNull(inputTokens);
        assertEquals(1200.0, inputTokens.count());

        Counter outputTokens = registry.find("agent.run.tokens.total")
                .tag("agent_id", "it-triage")
                .tag("type", "output")
                .counter();
        assertNotNull(outputTokens);
        assertEquals(350.0, outputTokens.count());

        // Model requests & Tool calls
        Counter modelRequests = registry.find("agent.run.model.requests")
                .tag("agent_id", "it-triage")
                .counter();
        assertNotNull(modelRequests);
        assertEquals(1.0, modelRequests.count());

        Counter toolCalls = registry.find("agent.run.tool.calls")
                .tag("agent_id", "it-triage")
                .tag("tool", "lookupTicket")
                .counter();
        assertNotNull(toolCalls);
        assertEquals(1.0, toolCalls.count());
    }

    @Test
    void incrementsModelRetryMetric() {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.MODEL_RETRY,
                "{\"agentId\":\"it-triage\",\"request\":1,\"attempt\":1}"));

        Counter retryCounter = registry.find("agent.run.model.retries")
                .tag("agent_id", "it-triage")
                .counter();
        assertNotNull(retryCounter);
        assertEquals(1.0, retryCounter.count());
    }

    @Test
    void incrementsCompactionAndDeliverableRetryMetricsWithoutPollutingActiveRuns() {
        UUID runId = UUID.randomUUID();
        // Emulate run start: activeRuns should become 1
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_STARTED,
                "{\"agentId\":\"it-triage\",\"goal\":\"fix incident\"}"));
        io.micrometer.core.instrument.Gauge activeRuns = registry.find("agent.runs.active").gauge();
        assertNotNull(activeRuns);
        assertEquals(1.0, activeRuns.value());
        assertEquals(1, listener.activeRuns());

        // Compaction triggered: should increment compaction counter, but NOT activeRuns
        listener.onEvent(new RunEvent(2, runId, Instant.now(), RunEvent.Type.COMPACTION_TRIGGERED,
                "{\"agentId\":\"it-triage\",\"compacted\":true,\"tokensAfter\":3000}"));
        Counter compactionCounter = registry.find("agent.run.context.compactions")
                .tag("agent_id", "it-triage")
                .counter();
        assertNotNull(compactionCounter);
        assertEquals(1.0, compactionCounter.count());
        assertEquals(1.0, activeRuns.value(), "activeRuns must not increase on compaction");
        assertEquals(1, listener.activeRuns());

        // Deliverable retry: should increment deliverable retry counter, but NOT activeRuns
        listener.onEvent(new RunEvent(3, runId, Instant.now(), RunEvent.Type.DELIVERABLE_RETRY,
                "{\"agentId\":\"it-triage\",\"completionRepair\":true,\"feedback\":\"Missing artifact\"}"));
        Counter retryCounter = registry.find("agent.run.deliverable.retries")
                .tag("agent_id", "it-triage")
                .counter();
        assertNotNull(retryCounter);
        assertEquals(1.0, retryCounter.count());
        assertEquals(1.0, activeRuns.value(), "activeRuns must not increase on deliverable retry");
        assertEquals(1, listener.activeRuns());

        // Run completed: activeRuns should return to 0
        listener.onEvent(new RunEvent(4, runId, Instant.now(), RunEvent.Type.RUN_FINISHED,
                "{\"agentId\":\"it-triage\",\"status\":\"COMPLETED\",\"reason\":\"DONE\",\"durationMillis\":500,\"metrics\":{\"modelRequests\":2,\"toolCalls\":1}}"));
        assertEquals(0.0, activeRuns.value());
        assertEquals(0, listener.activeRuns());
    }

    @Test
    void recordsRunTimeoutMetrics() {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_TIMEOUT,
                "{\"agentId\":\"it-triage\",\"timeoutType\":\"WAITING_TIMEOUT\",\"waitingSeconds\":900}"));

        Counter timeoutCounter = registry.find("agent.run.timeouts")
                .tag("agent_id", "it-triage")
                .tag("type", "WAITING_TIMEOUT")
                .counter();
        assertNotNull(timeoutCounter);
        assertEquals(1.0, timeoutCounter.count());
    }

    @Test
    void recordsApprovalAndClarificationMetrics() {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.APPROVAL_REQUESTED,
                "{\"agentId\":\"it-remediation\",\"action\":\"restartService\"}"));
        listener.onEvent(new RunEvent(2, runId, Instant.now(), RunEvent.Type.APPROVAL_RESOLVED,
                "{\"agentId\":\"it-remediation\",\"approved\":true}"));
        listener.onEvent(new RunEvent(3, runId, Instant.now(), RunEvent.Type.WAITING_INPUT,
                "{\"agentId\":\"it-remediation\",\"question\":\"Which cluster?\"}"));

        Counter approvalReq = registry.find("agent.run.approvals.requested")
                .tag("agent_id", "it-remediation")
                .counter();
        assertNotNull(approvalReq);
        assertEquals(1.0, approvalReq.count());

        Counter approvalRes = registry.find("agent.run.approvals.resolved")
                .tag("agent_id", "it-remediation")
                .tag("approved", "true")
                .counter();
        assertNotNull(approvalRes);
        assertEquals(1.0, approvalRes.count());

        Counter clarificationReq = registry.find("agent.run.clarifications.requested")
                .tag("agent_id", "it-remediation")
                .counter();
        assertNotNull(clarificationReq);
        assertEquals(1.0, clarificationReq.count());
    }

    @Test
    void lowCardinalityGuaranteedNoRunIdOrDynamicTextInTags() {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_FINISHED,
                "{\"agentId\":\"triage\",\"status\":\"FAILED\",\"reason\":\"TIMEOUT\",\"userText\":\"sensitive prompt\"}"));

        // Verify all registered meter tag names
        registry.getMeters().forEach(meter -> {
            meter.getId().getTags().forEach(tag -> {
                assertNotEquals("runId", tag.getKey());
                assertNotEquals("userText", tag.getKey());
                assertNotEquals("prompt", tag.getKey());
                assertFalse(tag.getValue().contains(runId.toString()));
                assertFalse(tag.getValue().contains("sensitive"));
            });
        });
    }
}
