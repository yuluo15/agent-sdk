package com.agent.agentcorelab.eval.replay;

import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * ModelGateway implementation backed by a ReplayRecorder.
 * <p>
 * Supports deterministic regression replay. When a request fingerprint is not found in the recorder,
 * it either throws {@link ReplayMissException} (strict offline mode) or records the live model call
 * (if a live fallback gateway is supplied).
 * </p>
 */
public class ReplayModelGateway implements ModelGateway {

    private final ReplayRecorder recorder;
    private final String modelName;
    private final Double temperature;
    private final Integer maxTokens;
    private final ModelGateway liveFallback;

    public ReplayModelGateway(ReplayRecorder recorder) {
        this(recorder, "eval-model", 0.0, 4096, null);
    }

    public ReplayModelGateway(ReplayRecorder recorder, String modelName, Double temperature, Integer maxTokens) {
        this(recorder, modelName, temperature, maxTokens, null);
    }

    public ReplayModelGateway(
            ReplayRecorder recorder,
            String modelName,
            Double temperature,
            Integer maxTokens,
            ModelGateway liveFallback
    ) {
        this.recorder = Objects.requireNonNull(recorder, "recorder cannot be null");
        this.modelName = modelName == null ? "eval-model" : modelName;
        this.temperature = temperature == null ? 0.0 : temperature;
        this.maxTokens = maxTokens == null ? 4096 : maxTokens;
        this.liveFallback = liveFallback;
    }

    @Override
    public ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools) {
        return openSession(instructions, Collections.emptyList(), goal, tools);
    }

    @Override
    public ModelSession openSession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
        ModelSession fallbackSession = liveFallback != null ? liveFallback.openSession(instructions, history, goal, tools) : null;
        return new ReplaySession(instructions, history, goal, tools, fallbackSession);
    }

    private class ReplaySession implements ModelSession {
        private final String instructions;
        private final List<ChatMessage> messages = new ArrayList<>();
        private final List<ToolDefinition> tools;
        private final ModelSession fallbackSession;

        ReplaySession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools, ModelSession fallbackSession) {
            this.instructions = instructions;
            if (history != null) {
                this.messages.addAll(history);
            }
            if (goal != null && !goal.isBlank()) {
                this.messages.add(ChatMessage.user(goal));
            }
            this.tools = tools == null ? Collections.emptyList() : List.copyOf(tools);
            this.fallbackSession = fallbackSession;
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            return nextResponse(timeout, null);
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> chunkListener) {
            String fingerprint = CanonicalFingerprint.compute(instructions, messages, tools, modelName, temperature, maxTokens);

            var recorded = recorder.lookup(fingerprint);
            if (recorded.isPresent()) {
                ModelResponse response = recorded.get().toModelResponse();
                if (chunkListener != null && response.text() != null && !response.text().isBlank()) {
                    chunkListener.accept(ModelStreamChunk.text(response.text()));
                }
                appendAssistantTurn(response);
                return response;
            }

            if (fallbackSession != null) {
                ModelResponse liveResponse = fallbackSession.nextResponse(timeout, chunkListener);
                if (liveResponse != null) {
                    recorder.put(ReplayRecord.fromModelResponse(fingerprint, liveResponse));
                    appendAssistantTurn(liveResponse);
                }
                return liveResponse;
            }

            throw new ReplayMissException(fingerprint);
        }

        private void appendAssistantTurn(ModelResponse response) {
            if (response.text() != null && !response.text().isBlank()) {
                messages.add(ChatMessage.assistant(response.text()));
            } else if (!response.toolCalls().isEmpty()) {
                StringBuilder toolSb = new StringBuilder("ToolCalls:");
                response.toolCalls().forEach(tc -> toolSb.append(" ").append(tc.name()).append("(").append(tc.argumentsJson()).append(")"));
                messages.add(ChatMessage.assistant(toolSb.toString()));
            }
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            if (fallbackSession != null) {
                fallbackSession.appendToolResult(callId, outcome);
            }
            String payload = "";
            if (outcome instanceof ToolOutcome.Success s) {
                payload = s.outputJson();
            } else if (outcome instanceof ToolOutcome.Failure f) {
                payload = f.message();
            }
            messages.add(ChatMessage.user("ToolResult[" + callId + "]: " + payload));
        }

        @Override
        public void appendUserMessage(String message) {
            if (fallbackSession != null) {
                fallbackSession.appendUserMessage(message);
            }
            messages.add(ChatMessage.user(message));
        }

        @Override
        public void appendRuntimeFeedback(String feedback) {
            if (fallbackSession != null) {
                fallbackSession.appendRuntimeFeedback(feedback);
            }
            messages.add(ChatMessage.system(feedback));
        }

        @Override
        public long estimatedInputTokens() {
            if (fallbackSession != null) {
                return fallbackSession.estimatedInputTokens();
            }
            long chars = instructions != null ? instructions.length() : 0;
            for (ChatMessage m : messages) {
                chars += m.content().length();
            }
            return chars / 3;
        }

        @Override
        public void close() {
            if (fallbackSession != null) {
                fallbackSession.close();
            }
        }
    }
}
