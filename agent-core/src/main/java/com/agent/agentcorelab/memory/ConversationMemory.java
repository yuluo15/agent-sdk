package com.agent.agentcorelab.memory;

import java.util.List;

/**
 * Service SPI for storing, retrieving, and managing multi-turn conversation dialogue history.
 */
public interface ConversationMemory {

    /**
     * Appends a message to the specified conversation history.
     *
     * @param conversationId unique conversation identifier
     * @param message        dialogue turn message
     */
    void append(String conversationId, ChatMessage message);

    /**
     * Retrieves an unmodifiable copy of the recent dialogue history for the given conversation ID.
     *
     * @param conversationId unique conversation identifier
     * @return ordered list of dialogue messages from oldest to newest
     */
    List<ChatMessage> getHistory(String conversationId);

    /**
     * Clears all recorded dialogue history for the given conversation ID.
     *
     * @param conversationId unique conversation identifier
     */
    void clear(String conversationId);

    /**
     * Explicitly triggers compaction on the specified conversation history if a compactor is configured.
     *
     * @param conversationId unique conversation identifier
     */
    default void compact(String conversationId) {}

    /**
     * Retrieves the active summary message for the given conversation, if one has been generated.
     *
     * @param conversationId unique conversation identifier
     * @return optional summary message
     */
    default java.util.Optional<ChatMessage> getSummary(String conversationId) {
        return java.util.Optional.empty();
    }

    /**
     * Returns the count of active conversations currently tracked in memory.
     */
    int activeConversationCount();
}
