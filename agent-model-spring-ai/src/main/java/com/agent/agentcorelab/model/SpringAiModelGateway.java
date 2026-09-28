package com.agent.agentcorelab.model;

import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** A non-streaming, one-request adapter. Only the Runner owns tool execution and iteration. */
public final class SpringAiModelGateway implements ModelGateway {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxOutputTokens;
    private final TokenEstimator estimator;

    public SpringAiModelGateway(String apiKey, String baseUrl, String model, int maxOutputTokens) {
        this(apiKey, baseUrl, model, maxOutputTokens, TokenEstimator.conservativeUtf8());
    }

    public SpringAiModelGateway(String apiKey, String baseUrl, String model, int maxOutputTokens,
                                TokenEstimator estimator) {
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.apiKey = required(apiKey, "agent.model.apiKey");
        this.model = required(model, "agent.model.model");
        this.baseUrl = required(baseUrl, "agent.model.baseUrl");
        try {
            URI uri = URI.create(baseUrl);
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                    || "[::1]".equals(host);
            if ((!"https".equalsIgnoreCase(uri.getScheme())
                    && !("http".equalsIgnoreCase(uri.getScheme()) && loopback))
                    || host == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("agent.model.baseUrl must use HTTPS (HTTP only for literal loopback hosts) without credentials, query or fragment");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("agent.model.maxOutputTokens must be positive");
        }
        this.maxOutputTokens = maxOutputTokens;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required when the model is enabled");
        }
        return value;
    }

    @Override
    public ModelSession openSession(String instructions, String goal, List<ToolDefinition> tools) {
        return openSession(instructions, List.of(), goal, tools);
    }

    @Override
    public ModelSession openSession(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
        return new Session(instructions, history, goal, List.copyOf(tools));
    }

    final class Session implements ModelSession {
        private final List<Message> messages = new ArrayList<>();
        private final List<ToolDefinition> tools;
        private final List<ToolCallback> callbacks;
        private final Map<String, String> pending = new LinkedHashMap<>();
        private final OneShotHttpClient transport;
        private boolean closed;
        private boolean failed;

        private Session(String instructions, List<ChatMessage> history, String goal, List<ToolDefinition> tools) {
            this.tools = tools;
            var names = new HashSet<String>();
            for (ToolDefinition tool : tools) {
                if (!names.add(tool.name())) {
                    throw new IllegalArgumentException("Duplicate model tool name");
                }
                try {
                    if (!JSON.readTree(tool.inputSchema()).isObject()) {
                        throw new IllegalArgumentException();
                    }
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Model tool schema must be a JSON object");
                }
            }
            callbacks = tools.stream().map(Session::schemaOnly).toList();
            messages.add(new SystemMessage(Objects.requireNonNull(instructions)));
            if (history != null && !history.isEmpty()) {
                for (ChatMessage msg : history) {
                    switch (msg.role()) {
                        case USER -> messages.add(new UserMessage(msg.content()));
                        case ASSISTANT -> messages.add(new AssistantMessage(msg.content()));
                        case SYSTEM -> messages.add(new SystemMessage(msg.content()));
                    }
                }
            }
            messages.add(new UserMessage(Objects.requireNonNull(goal)));
            transport = new OneShotHttpClient();
        }

        private static ToolCallback schemaOnly(ToolDefinition tool) {
            var definition = org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();
            return new ToolCallback() {
                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String input) {
                    throw new IllegalStateException("Tool execution belongs exclusively to the Runner");
                }
            };
        }

        @Override
        public ModelResponse nextResponse(Duration timeout) {
            requireReady();
            Objects.requireNonNull(timeout, "timeout");
            // OkHttp millisecond timers must never round a positive value down to zero.
            if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Model timeout must be between 1 ms and Integer.MAX_VALUE ms");
            }
            try (var http = transport.withTimeout(timeout)) {
                var clientOptions = ClientOptions.builder().httpClient(http).apiKey(apiKey)
                        .baseUrl(baseUrl).maxRetries(0).timeout(timeout).logLevel(com.openai.core.LogLevel.OFF).build();
                var options = OpenAiChatOptions.builder().model(model).maxCompletionTokens(maxOutputTokens)
                        .timeout(timeout).maxRetries(0).toolCallbacks(callbacks).strict(false).build();
                var chatModel = OpenAiChatModel.builder()
                        .openAiClient(new OpenAIClientImpl(clientOptions))
                        .openAiClientAsync(new OpenAIClientAsyncImpl(clientOptions))
                        .options(options).build();
                // No ChatClient, advisor, memory, retry template or ToolCallingManager execution.
                return accept(chatModel.call(new Prompt(List.copyOf(messages), options)));
            } catch (RuntimeException ex) {
                failed = true;
                for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                    if (cause instanceof ModelCallException safe) {
                        throw safe;
                    }
                    if (cause instanceof java.net.SocketTimeoutException
                            || cause instanceof HttpTimeoutException
                            || (cause instanceof InterruptedIOException && "timeout".equals(cause.getMessage()))) {
                        throw new ModelCallException("MODEL_TIMEOUT", new HttpTimeoutException("Model request timed out"));
                    }
                }
                // Never propagate provider bodies/headers or SDK exceptions into Runner logs.
                throw new ModelCallException("MODEL_REQUEST_FAILED");
            }
        }

        private ModelResponse accept(ChatResponse response) {
            if (response == null || response.getResults().size() != 1) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            var generation = response.getResult();
            AssistantMessage assistant = generation.getOutput();
            // Keep Spring AI's AssistantMessage and its supported metadata: reasoning_content
            // and function-call additional properties (including extra_content thought signatures).
            // This is not a lossless copy of every provider-native response field.
            messages.add(assistant);
            List<ToolCall> calls = assistant.getToolCalls().stream()
                    .map(c -> new ToolCall(c.id(), c.name(), c.arguments())).toList();
            var reason = finishReason(generation.getMetadata().getFinishReason(), assistant);
            if (reason == ModelResponse.FinishReason.TOOL_CALLS && calls.isEmpty()) {
                reason = ModelResponse.FinishReason.ERROR;
            }
            if (reason == ModelResponse.FinishReason.STOP && !calls.isEmpty()) {
                reason = ModelResponse.FinishReason.ERROR;
            }
            var ids = new HashSet<String>();
            for (ToolCall call : calls) {
                if (call.callId() == null || call.callId().isBlank() || !ids.add(call.callId())
                        || call.name() == null || call.name().isBlank()) {
                    reason = ModelResponse.FinishReason.ERROR;
                }
            }
            if (reason == ModelResponse.FinishReason.TOOL_CALLS) {
                calls.forEach(c -> pending.put(c.callId(), c.name()));
            } else if (reason != ModelResponse.FinishReason.STOP || assistant.getText() == null
                    || assistant.getText().isBlank()) {
                failed = true;
            }
            var usage = response.getMetadata().getUsage();
            Optional<ModelResponse.TokenUsage> tokens = usage == null || usage instanceof EmptyUsage
                    || usage.getPromptTokens() == null || usage.getCompletionTokens() == null
                    ? Optional.empty()
                    : Optional.of(new ModelResponse.TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens()));
            return new ModelResponse(assistant.getText(), calls, reason, tokens);
        }

        private ModelResponse.FinishReason finishReason(String reason, AssistantMessage assistant) {
            Object refusal = assistant.getMetadata().get("refusal");
            if (refusal instanceof String text && !text.isBlank()) {
                return ModelResponse.FinishReason.REFUSED;
            }
            return switch (reason == null ? "" : reason.toUpperCase(Locale.ROOT)) {
                case "STOP" -> ModelResponse.FinishReason.STOP;
                case "TOOL_CALLS" -> ModelResponse.FinishReason.TOOL_CALLS;
                case "LENGTH" -> ModelResponse.FinishReason.LENGTH;
                case "CONTENT_FILTER" -> ModelResponse.FinishReason.REFUSED;
                default -> ModelResponse.FinishReason.ERROR;
            };
        }

        @Override
        public void appendToolResult(String callId, ToolOutcome outcome) {
            requireOpen();
            String name = pending.get(callId);
            if (name == null) {
                throw new IllegalStateException("Tool result has no matching pending callId");
            }
            Objects.requireNonNull(outcome, "outcome");
            String result;
            if (outcome instanceof ToolOutcome.Success success) {
                result = Objects.requireNonNull(success.outputJson(), "outputJson");
            } else {
                ToolOutcome.Failure failure = (ToolOutcome.Failure) outcome;
                result = json(Map.of("ok", false, "error", Map.of("code", failure.code(), "message", failure.message())));
            }
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse(callId, name, result))).build());
            pending.remove(callId);
            if (outcome instanceof ToolOutcome.Failure failure && failure.terminal()) {
                failed = true;
            }
        }

        @Override
        public void appendUserMessage(String message) {
            requireReady();
            messages.add(new UserMessage(Objects.requireNonNull(message)));
        }

        @Override
        public void appendRuntimeFeedback(String feedback) {
            requireReady();
            messages.add(new UserMessage("Runtime completion-check feedback:\n" + Objects.requireNonNull(feedback)));
        }

        @Override
        public ModelResponse nextResponse(Duration timeout, Consumer<ModelStreamChunk> chunkListener) {
            ModelResponse response = nextResponse(timeout);
            if (chunkListener != null && response != null && response.text() != null && !response.text().isEmpty()) {
                chunkListener.accept(ModelStreamChunk.text(response.text()));
            }
            return response;
        }

        @Override
        public long estimatedInputTokens() {
            requireOpen();
            try {
                String contextJson = new String(JSON.writeValueAsBytes(Map.of("messages", messages, "tools", tools)),
                        StandardCharsets.UTF_8);
                long estimate = estimator.estimate(model, contextJson);
                if (estimate <= 0) {
                    throw new IllegalArgumentException();
                }
                long framing = Math.multiplyExact(32L, (long) messages.size() + tools.size() + 1);
                return Math.addExact(estimate, framing);
            } catch (Exception ex) {
                throw new ModelCallException("MODEL_CONTEXT_ESTIMATE_FAILED");
            }
        }

        @Override
        public boolean compactContext(long targetTokenBudget) {
            requireOpen();
            long current = estimatedInputTokens();
            if (current <= targetTokenBudget) {
                return false;
            }
            boolean changed = false;
            int keepRecent = 4;
            int bound = Math.max(2, messages.size() - keepRecent);
            for (int i = 2; i < bound; i++) {
                Message msg = messages.get(i);
                if (msg instanceof ToolResponseMessage toolMsg) {
                    List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>();
                    for (var resp : toolMsg.getResponses()) {
                        if (resp.responseData() != null && resp.responseData().length() > 200) {
                            compacted.add(new ToolResponseMessage.ToolResponse(
                                    resp.id(), resp.name(),
                                    "{\"ok\":true,\"_compacted\":true,\"note\":\"[Historical tool output compacted to fit context budget]\"}"
                            ));
                            changed = true;
                        } else {
                            compacted.add(resp);
                        }
                    }
                    if (changed) {
                        messages.set(i, ToolResponseMessage.builder().responses(compacted).build());
                    }
                }
            }
            return changed;
        }

        List<Message> nativeMessages() {
            return List.copyOf(messages);
        }

        private void requireOpen() {
            if (closed || failed) {
                throw new IllegalStateException("Model session is closed or failed");
            }
        }

        private void requireReady() {
            requireOpen();
            if (!pending.isEmpty()) {
                throw new IllegalStateException("All pending tool callIds must be answered before continuing");
            }
        }

        @Override
        public void close() {
            closed = true;
            pending.clear();
            messages.clear();
            transport.close();
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ModelCallException("MODEL_TOOL_RESULT_ENCODING_FAILED");
        }
    }
}
