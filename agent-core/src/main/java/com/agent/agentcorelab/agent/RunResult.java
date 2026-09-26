package com.agent.agentcorelab.agent;

public record RunResult(RunSnapshot snapshot, String message, String stopReason) {
}
