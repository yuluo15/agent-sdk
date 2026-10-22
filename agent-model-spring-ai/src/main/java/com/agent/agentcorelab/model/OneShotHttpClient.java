package com.agent.agentcorelab.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Bounded one-shot transport and raw protocol guard; the SDK still owns request/response mapping. */
final class OneShotHttpClient implements HttpClient {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final int MAX_JSON_DEPTH = 64;
    private static final ObjectMapper PROTOCOL_JSON = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH)
                    .maxStringLength(MAX_RESPONSE_BYTES).maxNumberLength(128).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final OkHttpClient client;
    private final boolean ownsTransport;
    private final Consumer<ModelStreamChunk> chunkListener;
    private boolean streamed;
    private final AtomicReference<Call> activeCall;

    OneShotHttpClient() {
        this(new OkHttpClient.Builder(), true, null, null);
    }

    OneShotHttpClient(Duration timeout) {
        this(withTimeout(new OkHttpClient.Builder(), timeout), true, null, null);
    }

    private OneShotHttpClient(OkHttpClient.Builder builder, boolean ownsTransport,
                              Consumer<ModelStreamChunk> chunkListener, AtomicReference<Call> activeCall) {
        client = builder.retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.ownsTransport = ownsTransport;
        this.chunkListener = chunkListener;
        this.activeCall = activeCall != null ? activeCall : new AtomicReference<>();
    }

    OneShotHttpClient withTimeout(Duration timeout) {
        return withTimeout(timeout, null);
    }

    OneShotHttpClient withTimeout(Duration timeout, Consumer<ModelStreamChunk> chunkListener) {
        // newBuilder shares only this session's pool/dispatcher; deadlines remain per-client.
        return new OneShotHttpClient(withTimeout(client.newBuilder(), timeout), false, chunkListener, this.activeCall);
    }

    boolean streamed() {
        return streamed;
    }

    private static OkHttpClient.Builder withTimeout(OkHttpClient.Builder builder, Duration timeout) {
        return builder.connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout);
    }

    OkHttpClient client() {
        return client;
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions options) {
        var source = request.body();
        try {
            final byte[] streamedRequestBytes;
            if (chunkListener != null && source != null) {
                var baos = new ByteArrayOutputStream();
                source.writeTo(baos);
                byte[] rawReq = baos.toByteArray();
                JsonNode reqNode = PROTOCOL_JSON.readTree(rawReq);
                if (reqNode instanceof ObjectNode obj) {
                    obj.put("stream", true);
                    streamedRequestBytes = PROTOCOL_JSON.writeValueAsBytes(obj);
                } else {
                    streamedRequestBytes = rawReq;
                }
            } else {
                streamedRequestBytes = null;
            }
            RequestBody body = source == null ? null : new RequestBody() {
                @Override
                public MediaType contentType() {
                    return source.contentType() == null ? null : MediaType.parse(source.contentType());
                }

                @Override
                public long contentLength() {
                    return streamedRequestBytes != null ? streamedRequestBytes.length : source.contentLength();
                }

                @Override
                public boolean isOneShot() {
                    // Also disallow OkHttp status-code follow-ups such as HTTP 408/503.
                    return true;
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    if (streamedRequestBytes != null) {
                        sink.write(streamedRequestBytes);
                    } else {
                        source.writeTo(sink.outputStream());
                    }
                }
            };
            var builder = new Request.Builder().url(request.url()).method(request.method().name(), body);
            for (String name : request.headers().names()) {
                for (String value : request.headers().values(name)) {
                    builder.addHeader(name, value);
                }
            }
            if (chunkListener != null) {
                builder.header("Accept", "text/event-stream, application/json");
            }
            // Consume a bounded decoded body under OkHttp's call/read deadline, not a timed Future.
            // Synchronous platform DNS resolution is not proven to obey a hard call deadline.
            Call call = client.newCall(builder.build());
            activeCall.set(call);
            try (Response response = call.execute()) {
                int status = response.code();
                if (!response.isSuccessful()) {
                    // Do not read, retain or let SDK error handling log a provider error body.
                    throw new ModelCallException("MODEL_REQUEST_FAILED", status);
                }
                String contentType = response.header("Content-Type", "");
                boolean isSse = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
                byte[] bytes;
                if (isSse && response.body() != null) {
                    bytes = consumeEventStream(response.body().byteStream(), chunkListener);
                    this.streamed = true;
                } else {
                    // OkHttp transparently decompresses gzip before exposing this stream.
                    bytes = response.body() == null ? new byte[0]
                            : response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw new ModelCallException("MODEL_RESPONSE_TOO_LARGE");
                    }
                    guardProtocol(bytes);
                }
                Headers.Builder headers = Headers.builder();
                for (int i = 0; i < response.headers().size(); i++) {
                    String headerName = response.headers().name(i);
                    if (isSse && "Content-Type".equalsIgnoreCase(headerName)) {
                        headers.put(headerName, "application/json; charset=utf-8");
                    } else {
                        headers.put(headerName, response.headers().value(i));
                    }
                }
                if (isSse && response.header("Content-Type") == null) {
                    headers.put("Content-Type", "application/json; charset=utf-8");
                }
                Headers sdkHeaders = headers.build();
                return new HttpResponse() {
                    private final InputStream stream = new ByteArrayInputStream(bytes);

                    @Override
                    public int statusCode() { return status; }

                    @Override
                    public Headers headers() { return sdkHeaders; }

                    @Override
                    public InputStream body() { return stream; }

                    @Override
                    public void close() { }
                };
            }
        } catch (IOException ex) {
            throw new OpenAIIoException("Model HTTP transport failed", ex);
        } finally {
            activeCall.set(null);
            if (source != null) {
                source.close();
            }
        }
    }

    private static byte[] consumeEventStream(InputStream rawStream, Consumer<ModelStreamChunk> listener) throws IOException {
        InputStream bounded = new InputStream() {
            private int totalBytes;

            @Override
            public int read() throws IOException {
                int b = rawStream.read();
                if (b != -1 && ++totalBytes > MAX_RESPONSE_BYTES) {
                    throw new ModelCallException("MODEL_RESPONSE_TOO_LARGE");
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = rawStream.read(b, off, len);
                if (n > 0) {
                    totalBytes += n;
                    if (totalBytes > MAX_RESPONSE_BYTES) {
                        throw new ModelCallException("MODEL_RESPONSE_TOO_LARGE");
                    }
                }
                return n;
            }
        };

        String id = null;
        String model = null;
        long created = 1L;
        String finishReason = null;
        JsonNode usageNode = null;
        boolean sawChoice = false;
        StringBuilder content = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder refusal = new StringBuilder();
        Map<Integer, ToolCallDelta> toolCalls = new LinkedHashMap<>();
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

        String line;
        while ((line = readUtf8Line(bounded, lineBuffer)) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")
                    || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) {
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            String payload = trimmed.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                break;
            }
            final JsonNode chunk;
            try {
                chunk = PROTOCOL_JSON.readTree(payload);
            } catch (IOException ex) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (chunk == null || !chunk.isObject()) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (chunk.hasNonNull("id")) {
                if (!chunk.get("id").isTextual()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                id = chunk.get("id").asText();
            }
            if (chunk.hasNonNull("model")) {
                if (!chunk.get("model").isTextual()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                model = chunk.get("model").asText();
            }
            if (chunk.hasNonNull("created")) {
                if (!chunk.get("created").isIntegralNumber()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                created = chunk.get("created").asLong();
            }
            if (chunk.hasNonNull("usage")) {
                if (!chunk.get("usage").isObject()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                usageNode = chunk.get("usage");
            }
            JsonNode choices = chunk.get("choices");
            if (choices == null || !choices.isArray() || choices.size() > 1) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (choices.isEmpty()) {
                if (!chunk.hasNonNull("usage")) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                continue;
            }
            sawChoice = true;
            JsonNode choice = choices.get(0);
            JsonNode index = choice.path("index");
            if (!choice.isObject() || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() != 0) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (choice.hasNonNull("finish_reason")) {
                if (!choice.get("finish_reason").isTextual() || choice.get("finish_reason").asText().isBlank()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                finishReason = choice.get("finish_reason").asText();
            }
            JsonNode delta = choice.path("delta");
            if (!delta.isObject()) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (delta.hasNonNull("role") && (!delta.get("role").isTextual() || !"assistant".equals(delta.get("role").asText()))) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            if (!optionalText(delta, "content") || !optionalText(delta, "refusal")
                    || !optionalText(delta, "reasoning_content") || !optionalText(delta, "reasoning")
                    || delta.hasNonNull("audio") || delta.hasNonNull("function_call")) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            String reasoningDelta = null;
            if (delta.hasNonNull("reasoning_content")) {
                reasoningDelta = delta.get("reasoning_content").asText();
            } else if (delta.hasNonNull("reasoning")) {
                reasoningDelta = delta.get("reasoning").asText();
            }
            if (reasoningDelta != null) {
                reasoningContent.append(reasoningDelta);
                if (!reasoningDelta.isEmpty() && listener != null) {
                    listener.accept(ModelStreamChunk.thinking(reasoningDelta));
                }
            }
            if (delta.hasNonNull("content")) {
                String contentDelta = delta.get("content").asText();
                content.append(contentDelta);
                if (!contentDelta.isEmpty() && listener != null) {
                    listener.accept(ModelStreamChunk.text(contentDelta));
                }
            }
            if (delta.hasNonNull("refusal")) {
                refusal.append(delta.get("refusal").asText());
            }
            if (delta.has("tool_calls")) {
                JsonNode calls = delta.get("tool_calls");
                if (calls == null || !calls.isArray()) {
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
                for (JsonNode call : calls) {
                    if (!call.isObject()) {
                        throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                    }
                    JsonNode tcIndexNode = call.path("index");
                    int tcIndex = (tcIndexNode.isIntegralNumber() && tcIndexNode.canConvertToInt())
                            ? tcIndexNode.intValue() : 0;
                    if (tcIndex < 0 || tcIndex > 128) {
                        throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                    }
                    ToolCallDelta acc = toolCalls.computeIfAbsent(tcIndex, k -> new ToolCallDelta());
                    if (call.hasNonNull("id")) {
                        if (!call.get("id").isTextual()) {
                            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                        }
                        acc.id.append(call.get("id").asText());
                    }
                    if (call.hasNonNull("type")) {
                        if (!call.get("type").isTextual()) {
                            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                        }
                        acc.type = call.get("type").asText();
                    }
                    if (call.hasNonNull("function")) {
                        JsonNode fn = call.get("function");
                        if (!fn.isObject() || !optionalText(fn, "name") || !optionalText(fn, "arguments")) {
                            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                        }
                        if (fn.hasNonNull("name")) {
                            acc.name.append(fn.get("name").asText());
                        }
                        if (fn.hasNonNull("arguments")) {
                            acc.arguments.append(fn.get("arguments").asText());
                        }
                    }
                    if (call.hasNonNull("extra_content")) {
                        if (!call.get("extra_content").isObject()) {
                            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                        }
                        acc.extraContent = call.get("extra_content");
                    }
                }
            }
        }

        if (!sawChoice || finishReason == null || finishReason.isBlank()) {
            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
        }

        ObjectNode root = PROTOCOL_JSON.createObjectNode();
        root.put("id", id != null ? id : "stream-response-id");
        root.put("object", "chat.completion");
        root.put("created", created);
        root.put("model", model != null ? model : "stream-model");
        ArrayNode choicesArray = root.putArray("choices");
        ObjectNode choiceObj = choicesArray.addObject();
        choiceObj.put("index", 0);
        choiceObj.put("finish_reason", finishReason);
        ObjectNode msgObj = choiceObj.putObject("message");
        msgObj.put("role", "assistant");
        msgObj.put("content", content.toString());
        if (reasoningContent.length() > 0) {
            msgObj.put("reasoning_content", reasoningContent.toString());
        }
        if (refusal.length() > 0) {
            msgObj.put("refusal", refusal.toString());
        }
        if (!toolCalls.isEmpty()) {
            ArrayNode tcArray = msgObj.putArray("tool_calls");
            for (ToolCallDelta tc : toolCalls.values()) {
                ObjectNode tcObj = tcArray.addObject();
                tcObj.put("id", tc.id.toString());
                tcObj.put("type", tc.type != null ? tc.type : "function");
                ObjectNode fnObj = tcObj.putObject("function");
                fnObj.put("name", tc.name.toString());
                fnObj.put("arguments", tc.arguments.toString());
                if (tc.extraContent != null) {
                    tcObj.set("extra_content", tc.extraContent);
                }
            }
        }
        if (usageNode != null) {
            root.set("usage", usageNode);
        }
        byte[] assembled = PROTOCOL_JSON.writeValueAsBytes(root);
        guardProtocol(assembled);
        return assembled;
    }

    private static String readUtf8Line(InputStream in, ByteArrayOutputStream lineBuffer) throws IOException {
        lineBuffer.reset();
        int b;
        boolean seenByte = false;
        while ((b = in.read()) != -1) {
            seenByte = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                lineBuffer.write(b);
            }
        }
        if (!seenByte && lineBuffer.size() == 0) {
            return null;
        }
        return lineBuffer.toString(StandardCharsets.UTF_8);
    }

    private static final class ToolCallDelta {
        private final StringBuilder id = new StringBuilder();
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private JsonNode extraContent;
    }

    private static void guardProtocol(byte[] bytes) {
        final JsonNode root;
        try {
            root = PROTOCOL_JSON.readTree(bytes);
        } catch (IOException ex) {
            // Parser exceptions may quote provider content. Never retain them as causes.
            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
        }
        if (root == null || !root.isObject()) {
            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) {
            // In particular, never let empty choices reach Spring AI's prompt-logging branch.
            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        JsonNode index = choice.path("index");
        if (!choice.isObject() || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() != 0
                || !choice.path("finish_reason").isTextual() || choice.path("finish_reason").asText().isBlank()
                || !message.isObject() || !"assistant".equals(message.path("role").asText())
                || !optionalText(message, "content") || !optionalText(message, "refusal")
                || !optionalText(message, "reasoning_content") || !optionalText(message, "reasoning")
                || message.hasNonNull("audio") || message.hasNonNull("function_call")) {
            throw new ModelCallException("MODEL_PROTOCOL_ERROR");
        }
        if (message.has("tool_calls")) {
            JsonNode calls = message.get("tool_calls");
            if (!calls.isArray()) {
                throw new ModelCallException("MODEL_PROTOCOL_ERROR");
            }
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                if (!call.isObject() || !"function".equals(call.path("type").asText())
                        || !call.path("id").isTextual() || !function.isObject()
                        || !function.path("name").isTextual() || !function.path("arguments").isTextual()) {
                    // Spring AI filters non-function calls; reject the whole response, including mixed lists.
                    throw new ModelCallException("MODEL_PROTOCOL_ERROR");
                }
            }
        }
        // Validation only: return the original bytes to the SDK. Do not rebuild or strip
        // supported reasoning_content or function-call extra_content thought signatures.
    }

    private static boolean optionalText(JsonNode node, String field) {
        return !node.hasNonNull(field) || node.get(field).isTextual();
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Only synchronous model calls are allowed"));
    }

    public void cancel() {
        Call call = activeCall.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
        client.dispatcher().cancelAll();
    }

    @Override
    public void close() {
        cancel();
        if (ownsTransport) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
