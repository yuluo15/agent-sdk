package com.agent.agentcorelab.memory.compaction;

import com.agent.agentcorelab.memory.ChatMessage;

import java.util.List;

/**
 * Strategy interface for compacting long conversation dialogue histories.
 * Folds older dialogue turns into a consolidated factual summary while retaining
 * recent turns verbatim, avoiding context token budget overflow without losing vital history.
 */
public interface ConversationCompactor {

    /**
     * Compacts an existing conversation history into a condensed representation.
     *
     * @param history ordered list of dialogue messages from oldest to newest
     * @return compacted list of dialogue messages
     */
    List<ChatMessage> compact(List<ChatMessage> history);
}
