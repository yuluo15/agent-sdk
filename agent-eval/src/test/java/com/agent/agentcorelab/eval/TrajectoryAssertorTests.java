package com.agent.agentcorelab.eval;

import com.agent.agentcorelab.eval.assertion.AssertionOutcome;
import com.agent.agentcorelab.eval.assertion.FailureCode;
import com.agent.agentcorelab.eval.assertion.TrajectoryAssertor;
import com.agent.agentcorelab.eval.assertion.TrajectoryAssertor.ToolInvocation;
import com.agent.agentcorelab.eval.dataset.TrajectoryAssertion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrajectoryAssertorTests {

    @Test
    void testPartialOrderDagSuccess() {
        TrajectoryAssertion assertion = new TrajectoryAssertion(
                List.of("lookupTicket", "analyzeSystemLogs"),
                List.of("executeRemediation"),
                List.of("lookupTicket MUST_BEFORE analyzeSystemLogs"),
                List.of("searchKnowledgeBase"),
                2
        );

        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1001\"}"),
                new ToolInvocation("searchKnowledgeBase", "{\"query\":\"VPN\"}"),
                new ToolInvocation("analyzeSystemLogs", "{\"timeRange\":\"24h\"}")
        );

        AssertionOutcome outcome = TrajectoryAssertor.evaluate(assertion, invocations);
        assertTrue(outcome.passed());
    }

    @Test
    void testPartialOrderDagViolation() {
        TrajectoryAssertion assertion = new TrajectoryAssertion(
                List.of("lookupTicket", "analyzeSystemLogs"),
                List.of(),
                List.of("lookupTicket MUST_BEFORE analyzeSystemLogs"),
                List.of(),
                2
        );

        // analyzeSystemLogs invoked BEFORE lookupTicket
        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("analyzeSystemLogs", "{\"timeRange\":\"24h\"}"),
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1001\"}")
        );

        AssertionOutcome outcome = TrajectoryAssertor.evaluate(assertion, invocations);
        assertFalse(outcome.passed());
        assertTrue(outcome.failureCodes().contains(FailureCode.TRAJECTORY_WRONG_ORDER));
    }

    @Test
    void testMissingRequiredTool() {
        TrajectoryAssertion assertion = new TrajectoryAssertion(
                List.of("lookupTicket", "analyzeSystemLogs"),
                List.of(),
                List.of(),
                List.of(),
                2
        );

        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1001\"}")
        );

        AssertionOutcome outcome = TrajectoryAssertor.evaluate(assertion, invocations);
        assertFalse(outcome.passed());
        assertTrue(outcome.failureCodes().contains(FailureCode.TRAJECTORY_MISSING_TOOL));
    }

    @Test
    void testMaxRepeatedCallsExceeded() {
        TrajectoryAssertion assertion = new TrajectoryAssertion(
                List.of("lookupTicket"),
                List.of(),
                List.of(),
                List.of(),
                2
        );

        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1\"}"),
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1\"}"),
                new ToolInvocation("lookupTicket", "{\"ticketId\":\"TCK-1\"}")
        );

        AssertionOutcome outcome = TrajectoryAssertor.evaluate(assertion, invocations);
        assertFalse(outcome.passed());
        assertTrue(outcome.failureCodes().contains(FailureCode.TRAJECTORY_REPEAT_NO_PROGRESS));
    }
}
