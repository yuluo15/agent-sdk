package com.agent.agentcorelab.agent;

import java.util.Optional;
import java.util.UUID;

public interface AgentRunner {

    RunResult run(AgentSpec agent, RunRequest request);

    RunResult resume(UUID runId, String answer);

    void cancel(UUID runId);

    RunSnapshot snapshot(UUID runId);

    Optional<RunSnapshot> currentSnapshot();
}
