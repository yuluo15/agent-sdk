package com.agent.agentcorelab.agent;

@FunctionalInterface
public interface AgentRunnerFactory {
    // Each caller owns a fresh runner and must close it after its session ends.
    DefaultAgentRunner create();
}
