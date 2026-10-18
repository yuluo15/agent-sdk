package com.agent.agentcorelab.eval;

import com.agent.agentcorelab.eval.assertion.AssertionOutcome;
import com.agent.agentcorelab.eval.assertion.EvidenceAssertor;
import com.agent.agentcorelab.eval.assertion.FailureCode;
import com.agent.agentcorelab.eval.dataset.EvidenceAssertion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceAssertorTests {

    @Test
    void testStructuredCitationSuccess() {
        EvidenceAssertion assertion = new EvidenceAssertion(
                List.of("ticket:TCK-1001", "kb:KB-VPN-602"),
                true,
                true
        );

        String output = """
                排障分析完成：用户 VPN 握手失败是由于 MTU 配置不一致导致。
                
                【参考依据】: [ticket:TCK-1001] [kb:KB-VPN-602]
                """;

        List<String> observations = List.of(
                "{\"ticketId\":\"ticket:TCK-1001\",\"title\":\"VPN 602\"}",
                "[{\"kbId\":\"kb:KB-VPN-602\",\"title\":\"VPN 602 手册\"}]"
        );

        AssertionOutcome outcome = EvidenceAssertor.evaluate(assertion, output, observations);
        assertTrue(outcome.passed());
    }

    @Test
    void testMissingCitationBlock() {
        EvidenceAssertion assertion = new EvidenceAssertion(
                List.of("ticket:TCK-1001"),
                true,
                false
        );

        String output = "排障结论：根据 ticket:TCK-1001，问题已确认。"; // 没有【参考依据】块

        AssertionOutcome outcome = EvidenceAssertor.evaluate(assertion, output, List.of("TCK-1001"));
        assertFalse(outcome.passed());
        assertTrue(outcome.failureCodes().contains(FailureCode.CITATION_FORMAT_MISSING));
    }

    @Test
    void testFabricatedEvidenceIdRejected() {
        EvidenceAssertion assertion = new EvidenceAssertion(
                List.of("ticket:TCK-1001"),
                true,
                true
        );

        String output = """
                排障完成。
                【参考依据】: [ticket:TCK-1001] [kb:KB-FAKE-999]
                """;

        // Observations contain TCK-1001, but NOT FAKE-999
        List<String> observations = List.of("{\"ticketId\":\"ticket:TCK-1001\"}");

        AssertionOutcome outcome = EvidenceAssertor.evaluate(assertion, output, observations);
        assertFalse(outcome.passed());
        assertTrue(outcome.failureCodes().contains(FailureCode.EVIDENCE_FABRICATED));
    }
}
