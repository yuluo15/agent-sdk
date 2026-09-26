package com.agent.agentcorelab.agent;

@FunctionalInterface
public interface CompletionCheck {

    Result check(RunSnapshot run, String finalAnswer);

    record Result(boolean complete, String feedback) {
    }
}
