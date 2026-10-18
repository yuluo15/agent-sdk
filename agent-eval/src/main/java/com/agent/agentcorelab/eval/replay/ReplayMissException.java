package com.agent.agentcorelab.eval.replay;

/**
 * Thrown when a request fingerprint has no matching entry in the deterministic replay dataset.
 * <p>
 * Under the Phase 0 Replay Protocol, replay misses must fail explicitly to prevent silent drift
 * into unrecorded live model calls during regression test runs.
 * </p>
 */
public class ReplayMissException extends RuntimeException {

    private final String fingerprint;

    public ReplayMissException(String fingerprint) {
        super("Deterministic replay miss for request fingerprint: " + fingerprint
                + ". Recorded response is absent or outdated for this prompt/tools/model configuration.");
        this.fingerprint = fingerprint;
    }

    public String fingerprint() {
        return fingerprint;
    }
}
