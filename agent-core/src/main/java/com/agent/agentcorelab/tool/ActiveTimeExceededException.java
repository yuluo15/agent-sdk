package com.agent.agentcorelab.tool;

public final class ActiveTimeExceededException extends RuntimeException {
    public ActiveTimeExceededException() {
        super("Active processing time exceeded");
    }
}
