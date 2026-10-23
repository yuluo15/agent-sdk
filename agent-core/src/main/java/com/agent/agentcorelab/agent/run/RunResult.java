package com.agent.agentcorelab.agent.run;

public record RunResult(RunSnapshot snapshot, String message, String stopReason) {
}
