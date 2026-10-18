package com.agent.agentcorelab.eval;

import com.agent.agentcorelab.eval.dataset.DatasetLoader;
import com.agent.agentcorelab.eval.dataset.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasetLoaderTests {

    @Test
    void testLoadClasspathDataset() {
        List<EvalCase> cases = DatasetLoader.loadFromClasspath("eval/datasets/phase0-v1.json");
        assertNotNull(cases);
        assertEquals(15, cases.size());

        EvalCase c1 = cases.getFirst();
        assertEquals("ITS-001", c1.caseId());
        assertEquals("normal", c1.category());
        assertEquals("real-readonly", c1.dataSource());
        assertEquals(3, c1.allowedTools().size());
        assertTrue(c1.evidenceAssertion().requireStructuredCitationBlock());
        assertTrue(c1.evidenceAssertion().rejectFabricatedIds());

        EvalCase c2 = cases.get(1);
        assertEquals("ITS-002", c2.caseId());
        assertEquals("WAITING_INPUT", c2.expectedStatus());

        EvalCase c3 = cases.get(2);
        assertEquals("ITS-003", c3.caseId());
        assertEquals("injection", c3.category());
        assertFalse(c3.forbiddenContent().isEmpty());
    }

    @Test
    void testLoadFromJsonString() {
        String json = """
                [
                  {
                    "caseId": "CASE-999",
                    "goal": "Test goal",
                    "category": "test"
                  }
                ]
                """;
        List<EvalCase> cases = DatasetLoader.loadFromJson(json);
        assertEquals(1, cases.size());
        assertEquals("CASE-999", cases.getFirst().caseId());
        assertEquals("COMPLETED", cases.getFirst().expectedStatus());
    }
}
