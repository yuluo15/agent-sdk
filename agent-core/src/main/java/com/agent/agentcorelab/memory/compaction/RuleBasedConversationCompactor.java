package com.agent.agentcorelab.memory.compaction;

import com.agent.agentcorelab.memory.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, zero-token, rule-based compactor that summarizes older dialogue turns
 * into a structured factual background block while preserving recent dialogue turns verbatim.
 */
public final class RuleBasedConversationCompactor implements ConversationCompactor {

    public static final String SUMMARY_PREFIX = "【前文对话历史背景摘要】";
    public static final int DEFAULT_MAX_TURN_CHARS = 240;

    private final CompactionPolicy policy;
    private final int maxTurnChars;

    public RuleBasedConversationCompactor() {
        this(CompactionPolicy.defaultPolicy(), DEFAULT_MAX_TURN_CHARS);
    }

    public RuleBasedConversationCompactor(CompactionPolicy policy) {
        this(policy, DEFAULT_MAX_TURN_CHARS);
    }

    public RuleBasedConversationCompactor(CompactionPolicy policy, int maxTurnChars) {
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        if (maxTurnChars <= 20) {
            throw new IllegalArgumentException("maxTurnChars must be > 20, got: " + maxTurnChars);
        }
        this.maxTurnChars = maxTurnChars;
    }

    public CompactionPolicy policy() {
        return policy;
    }

    @Override
    public List<ChatMessage> compact(List<ChatMessage> history) {
        if (history == null || history.size() < policy.triggerMessageCount()) {
            return history != null ? List.copyOf(history) : List.of();
        }

        int retainCount = policy.retainRecentCount();
        int splitIndex = history.size() - retainCount;

        List<ChatMessage> older = history.subList(0, splitIndex);
        List<ChatMessage> recent = history.subList(splitIndex, history.size());

        StringBuilder summaryBuilder = new StringBuilder();
        summaryBuilder.append(SUMMARY_PREFIX).append("\n");

        for (ChatMessage msg : older) {
            if (msg.role() == ChatMessage.Role.SYSTEM && msg.content().startsWith(SUMMARY_PREFIX)) {
                // Extract existing summary body without header
                String existingBody = msg.content().substring(SUMMARY_PREFIX.length()).trim();
                if (!existingBody.isBlank()) {
                    summaryBuilder.append(existingBody).append("\n");
                }
                continue;
            }

            String content = msg.content().trim();
            if (content.length() > maxTurnChars) {
                content = content.substring(0, maxTurnChars) + "... (已截断)";
            }
            content = content.replace("\n", " ");

            switch (msg.role()) {
                case USER -> summaryBuilder.append("- [用户输入/需求]: ").append(content).append("\n");
                case ASSISTANT -> summaryBuilder.append("- [助手回复/结论]: ").append(content).append("\n");
                case SYSTEM -> summaryBuilder.append("- [系统状态/规则]: ").append(content).append("\n");
            }
        }

        ChatMessage consolidatedSummary = ChatMessage.system(summaryBuilder.toString().trim());

        List<ChatMessage> compacted = new ArrayList<>(retainCount + 1);
        compacted.add(consolidatedSummary);
        compacted.addAll(recent);

        return List.copyOf(compacted);
    }
}
