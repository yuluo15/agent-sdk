package com.agent.agentcorelab.agent.run;

import java.util.List;

public record PendingQuestion(String callId, String question, List<String> options) {
    public PendingQuestion {
        options = List.copyOf(options);
    }
}
