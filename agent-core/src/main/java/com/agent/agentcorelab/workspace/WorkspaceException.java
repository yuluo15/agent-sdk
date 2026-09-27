package com.agent.agentcorelab.workspace;

import java.io.IOException;

/** Safe, model-visible diagnostics; never include configured absolute paths. */
public final class WorkspaceException extends IOException {
    private final String code;
    private final boolean terminal;

    public WorkspaceException(String code, String message, boolean terminal) {
        super(message);
        this.code = code;
        this.terminal = terminal;
    }

    public String code() { return code; }
    public boolean terminal() { return terminal; }
}
