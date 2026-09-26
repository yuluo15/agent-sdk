package com.agent.agentcorelab.model;

/** Safe error boundary: provider bodies, request headers and credentials are not exposed. */
public final class ModelCallException extends RuntimeException {
    private final String code;

    ModelCallException(String code) {
        super(code);
        this.code = code;
    }

    ModelCallException(String code, java.net.http.HttpTimeoutException timeout) {
        super(code, timeout);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
