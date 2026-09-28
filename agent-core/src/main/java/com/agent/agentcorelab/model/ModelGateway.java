package com.agent.agentcorelab.model;

import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.tool.ToolDefinition;

import java.util.List;

public interface ModelGateway {

    ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools);

    /**
     * Opens a model session pre-loaded with prior conversation history turns.
     * Default implementation gracefully formats history into the goal text for adapters
     * that do not natively manage multi-message structures.
     */
    default ModelSession openSession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
        if (history == null || history.isEmpty()) {
            return openSession(instructions, goal, tools);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- Previous Conversation History ---\n");
        for (ChatMessage msg : history) {
            sb.append(msg.role().name()).append(": ").append(msg.content()).append("\n");
        }
        sb.append("--- End of History ---\n\n");
        sb.append(goal);
        return openSession(instructions, sb.toString(), tools);
    }
}
