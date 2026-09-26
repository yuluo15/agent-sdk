package com.agent.agentcorelab.trace;

@FunctionalInterface
public interface RunEventSink {

    void publish(RunEvent event);
}
