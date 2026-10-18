package com.agent.agentcorelab.memory.compaction;

/**
 * Configuration policy governing when and how dialogue turns are compacted.
 *
 * @param triggerMessageCount threshold of total messages that triggers compaction (e.g. 16)
 * @param retainRecentCount   number of recent messages to preserve verbatim (e.g. 6)
 */
public record CompactionPolicy(int triggerMessageCount, int retainRecentCount) {

    public static final int DEFAULT_TRIGGER_COUNT = 16;
    public static final int DEFAULT_RETAIN_COUNT = 6;

    public CompactionPolicy {
        if (triggerMessageCount <= 2) {
            throw new IllegalArgumentException("triggerMessageCount must be > 2, got: " + triggerMessageCount);
        }
        if (retainRecentCount < 1 || retainRecentCount >= triggerMessageCount) {
            throw new IllegalArgumentException("retainRecentCount must be >= 1 and < triggerMessageCount ("
                    + triggerMessageCount + "), got: " + retainRecentCount);
        }
    }

    public static CompactionPolicy defaultPolicy() {
        return new CompactionPolicy(DEFAULT_TRIGGER_COUNT, DEFAULT_RETAIN_COUNT);
    }
}
