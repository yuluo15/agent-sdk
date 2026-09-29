package com.agent.agentcorelab.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe, bounded in-memory implementation of {@link ConversationMemory}.
 * <p>
 * Enforces two defensive boundaries:
 * 1. Per-conversation sliding window: trims oldest messages when exceeding {@code maxMessagesPerConversation}.
 * 2. Total conversation capacity: evicts least recently accessed conversations when exceeding {@code maxConversations}.
 */
public final class InMemoryConversationMemory implements ConversationMemory {

    public static final int DEFAULT_MAX_CONVERSATIONS = 1000;
    public static final int DEFAULT_MAX_MESSAGES_PER_CONVERSATION = 40;

    private final int maxConversations;
    private final int maxMessagesPerConversation;
    private final ConversationCompactor compactor;
    private final Map<String, List<ChatMessage>> store;

    public InMemoryConversationMemory() {
        this(DEFAULT_MAX_CONVERSATIONS, DEFAULT_MAX_MESSAGES_PER_CONVERSATION, new RuleBasedConversationCompactor());
    }

    public InMemoryConversationMemory(int maxConversations, int maxMessagesPerConversation) {
        this(maxConversations, maxMessagesPerConversation, new RuleBasedConversationCompactor());
    }

    public InMemoryConversationMemory(int maxConversations, int maxMessagesPerConversation, ConversationCompactor compactor) {
        if (maxConversations <= 0 || maxMessagesPerConversation <= 0) {
            throw new IllegalArgumentException("Capacities must be strictly positive");
        }
        this.maxConversations = maxConversations;
        this.maxMessagesPerConversation = maxMessagesPerConversation;
        this.compactor = compactor;
        this.store = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<ChatMessage>> eldest) {
                return size() > InMemoryConversationMemory.this.maxConversations;
            }
        };
    }

    @Override
    public synchronized void append(String conversationId, ChatMessage message) {
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        List<ChatMessage> history = store.computeIfAbsent(conversationId, k -> new ArrayList<>());
        history.add(message);

        if (compactor != null) {
            List<ChatMessage> compacted = compactor.compact(history);
            if (compacted != null && compacted.size() < history.size()) {
                history.clear();
                history.addAll(compacted);
            }
        }

        while (history.size() > maxMessagesPerConversation) {
            history.remove(0);
        }
    }

    @Override
    public synchronized void compact(String conversationId) {
        if (conversationId == null || compactor == null) {
            return;
        }
        List<ChatMessage> history = store.get(conversationId);
        if (history != null && !history.isEmpty()) {
            List<ChatMessage> compacted = compactor.compact(history);
            if (compacted != null && compacted.size() < history.size()) {
                history.clear();
                history.addAll(compacted);
            }
        }
    }

    @Override
    public synchronized java.util.Optional<ChatMessage> getSummary(String conversationId) {
        if (conversationId == null) {
            return java.util.Optional.empty();
        }
        List<ChatMessage> history = store.get(conversationId);
        if (history != null && !history.isEmpty()) {
            ChatMessage first = history.getFirst();
            if (first.role() == ChatMessage.Role.SYSTEM
                    && first.content().startsWith(RuleBasedConversationCompactor.SUMMARY_PREFIX)) {
                return java.util.Optional.of(first);
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public synchronized List<ChatMessage> getHistory(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<ChatMessage> history = store.get(conversationId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return List.copyOf(history);
    }

    @Override
    public synchronized void clear(String conversationId) {
        if (conversationId != null) {
            store.remove(conversationId);
        }
    }

    @Override
    public synchronized int activeConversationCount() {
        return store.size();
    }
}
