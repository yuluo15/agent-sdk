package com.agent.agentcorelab.model;

/** Safe error boundary: provider bodies, request headers and credentials are not exposed. */
public final class ModelCallException extends RuntimeException {
    private final String code;
    private final int statusCode;
    private final boolean transientError;

    ModelCallException(String code) {
        this(code, 0, false);
    }

    ModelCallException(String code, int statusCode) {
        this(code, statusCode, isTransientStatus(statusCode));
    }

    ModelCallException(String code, int statusCode, boolean transientError) {
        super(code);
        this.code = code;
        this.statusCode = statusCode;
        this.transientError = transientError;
    }

    ModelCallException(String code, java.net.http.HttpTimeoutException timeout) {
        super(code, timeout);
        this.code = code;
        this.statusCode = 408;
        this.transientError = true;
    }

    public String code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isTransient() {
        return transientError;
    }

    public static boolean isTransientStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
    }
}
