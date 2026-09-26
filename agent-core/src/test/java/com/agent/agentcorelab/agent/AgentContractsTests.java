package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.workspace.Workspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentContractsTests {
    private final RunLimits limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
    private final Workspace workspace = new Workspace(Path.of("input"), Path.of("output"), Path.of("traces"));
    private final CompletionCheck check = (run, answer) -> new CompletionCheck.Result(true, "");

    @Test
    void rejectsMissingAndOversizedUserGoals() {
        assertThrows(IllegalArgumentException.class, () -> new RunRequest(" ", workspace));
        assertThrows(IllegalArgumentException.class, () -> new RunRequest("x".repeat(16001), workspace));
        assertThrows(NullPointerException.class, () -> new RunRequest("Inspect", null));
    }

    @Test
    void cannotConstructAScenarioWithoutItsCompletionBoundary() {
        assertThrows(NullPointerException.class, () -> new AgentSpec("test", "Instructions", Set.of(), limits, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentSpec("bad/name", "Instructions", Set.of(), limits, check));
        assertThrows(IllegalArgumentException.class, () -> new AgentSpec("test", " ", Set.of(), limits, check));
    }

    @Test
    void scenarioPermissionsAreCopiedAtConstruction() {
        Set<String> permissions = new HashSet<>(Set.of("read"));
        AgentSpec spec = new AgentSpec("test", "Instructions", permissions, limits, check);
        permissions.add("write");
        assertEquals(Set.of("read"), spec.toolNames());
        assertThrows(UnsupportedOperationException.class, () -> spec.toolNames().add("delete"));
    }
}
