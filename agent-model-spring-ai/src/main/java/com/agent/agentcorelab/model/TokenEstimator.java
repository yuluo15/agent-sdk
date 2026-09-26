package com.agent.agentcorelab.model;

import java.nio.charset.StandardCharsets;

/** Estimates serialized context tokens for a model; the gateway adds message/tool framing separately. */
@FunctionalInterface
public interface TokenEstimator {
    long estimate(String model, String contextJson);

    static TokenEstimator conservativeUtf8() {
        return (model, contextJson) -> contextJson.getBytes(StandardCharsets.UTF_8).length;
    }
}
