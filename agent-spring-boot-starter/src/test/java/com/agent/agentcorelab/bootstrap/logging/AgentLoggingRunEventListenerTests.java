package com.agent.agentcorelab.bootstrap.logging;

import com.agent.agentcorelab.trace.RunEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AgentLoggingRunEventListenerTests {

    private AgentLoggingRunEventListener listener;
    private AgentLoggingProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentLoggingProperties();
        listener = new AgentLoggingRunEventListener(properties);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void logsRunStartedAndSetsMdc(CapturedOutput output) {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_STARTED,
                "{\"agentId\":\"it-triage\",\"goal\":\"Diagnose server 502 error\"}"));

        assertTrue(output.getOut().contains("Agent run STARTED"));
        assertTrue(output.getOut().contains("Diagnose server 502 error"));
        assertTrue(output.getOut().contains(runId.toString()));
        assertEquals(runId.toString(), MDC.get("agent.runId"));
        assertEquals("it-triage", MDC.get("agent.id"));
    }

    @Test
    void logsModelFinishedWithTokens(CapturedOutput output) {
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(2, runId, Instant.now(), RunEvent.Type.MODEL_FINISHED,
                "{\"agentId\":\"it-triage\",\"request\":1,\"modelMillis\":820,\"usage\":{\"inputTokens\":1200,\"outputTokens\":350}}"));

        assertTrue(output.getOut().contains("Model request #1 completed in 820ms"));
        assertTrue(output.getOut().contains("in=1200, out=350"));
    }

    @Test
    void logsToolFinishedAttributingSuccessAndUserFailure(CapturedOutput output) {
        UUID runId = UUID.randomUUID();

        // Success
        listener.onEvent(new RunEvent(3, runId, Instant.now(), RunEvent.Type.TOOL_FINISHED,
                "{\"agentId\":\"it-triage\",\"tool\":\"lookupTicket\",\"callId\":\"call_01\",\"outcome\":\"SUCCESS\"}"));
        assertTrue(output.getOut().contains("Tool [lookupTicket] executed successfully"));

        // User tool failure with explicit marker [USER_TOOL_ERROR]
        listener.onEvent(new RunEvent(4, runId, Instant.now(), RunEvent.Type.TOOL_FINISHED,
                "{\"agentId\":\"it-triage\",\"tool\":\"queryDb\",\"callId\":\"call_02\",\"outcome\":\"RECORD_NOT_FOUND\",\"error\":\"Ticket 9999 does not exist in CRM\"}"));
        assertTrue(output.getOut().contains("[USER_TOOL_ERROR]"));
        assertTrue(output.getOut().contains("Tool [queryDb] failed with code [RECORD_NOT_FOUND]"));
        assertTrue(output.getOut().contains("Ticket 9999 does not exist in CRM"));
    }

    @Test
    void logsApprovalInterceptionAndResolution(CapturedOutput output) {
        UUID runId = UUID.randomUUID();

        // High-risk interception
        listener.onEvent(new RunEvent(5, runId, Instant.now(), RunEvent.Type.APPROVAL_REQUESTED,
                "{\"agentId\":\"it-remediation\",\"tool\":\"restartCluster\",\"callId\":\"call_03\",\"action\":\"Restart production cluster pod-01\"}"));
        assertTrue(output.getOut().contains("[HITL_APPROVAL_REQUIRED]"));
        assertTrue(output.getOut().contains("Restart production cluster pod-01"));

        // Approved
        listener.onEvent(new RunEvent(6, runId, Instant.now(), RunEvent.Type.APPROVAL_RESOLVED,
                "{\"agentId\":\"it-remediation\",\"approved\":true}"));
        assertTrue(output.getOut().contains("[HITL_APPROVAL_RESOLVED]"));
        assertTrue(output.getOut().contains("APPROVED"));

        // Rejected
        listener.onEvent(new RunEvent(7, runId, Instant.now(), RunEvent.Type.APPROVAL_RESOLVED,
                "{\"agentId\":\"it-remediation\",\"approved\":false}"));
        assertTrue(output.getOut().contains("REJECTED"));
    }

    @Test
    void logsTerminalStatusesWithClearAttributionAndCleansMdc(CapturedOutput output) {
        UUID runId = UUID.randomUUID();

        // Model failure -> [MODEL_GATEWAY_ERROR]
        listener.onEvent(new RunEvent(8, runId, Instant.now(), RunEvent.Type.RUN_FINISHED,
                "{\"agentId\":\"it-triage\",\"status\":\"FAILED\",\"reason\":\"MODEL_TIMEOUT\",\"activeMillis\":60000}"));

        assertTrue(output.getOut().contains("[MODEL_GATEWAY_ERROR]"));
        assertTrue(output.getOut().contains("Agent run FAILED with reason: MODEL_TIMEOUT"));
        // MDC cleared after RUN_FINISHED
        assertNull(MDC.get("agent.runId"));
        assertNull(MDC.get("agent.id"));

        // Budget exhaustion -> [BUDGET_EXHAUSTED]
        listener.onEvent(new RunEvent(9, runId, Instant.now(), RunEvent.Type.RUN_FINISHED,
                "{\"agentId\":\"it-triage\",\"status\":\"STOPPED\",\"reason\":\"MAX_MODEL_REQUESTS\",\"activeMillis\":15000,\"modelRequests\":16}"));
        assertTrue(output.getOut().contains("[BUDGET_EXHAUSTED]"));
        assertTrue(output.getOut().contains("Agent run STOPPED due to MAX_MODEL_REQUESTS"));
    }

    @Test
    void respectsDisabledSetting(CapturedOutput output) {
        properties.setEnabled(false);
        UUID runId = UUID.randomUUID();
        listener.onEvent(new RunEvent(1, runId, Instant.now(), RunEvent.Type.RUN_STARTED,
                "{\"agentId\":\"test\",\"goal\":\"do something\"}"));

        assertFalse(output.getOut().contains("Agent run STARTED"));
        assertNull(MDC.get("agent.runId"));
    }
}
