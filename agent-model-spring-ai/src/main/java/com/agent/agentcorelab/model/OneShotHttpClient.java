package com.agent.agentcorelab.model;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.RequestOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

    OneShotHttpClient() {
        this(new OkHttpClient.Builder(), true);
    }

    OneShotHttpClient(Duration timeout) {
        this(withTimeout(new OkHttpClient.Builder(), timeout), true);
    }

    private OneShotHttpClient(OkHttpClient.Builder builder, boolean ownsTransport) {
        client = builder.retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.ownsTransport = ownsTransport;
    }

    OneShotHttpClient withTimeout(Duration timeout) {
        // newBuilder shares only this session's pool/dispatcher; deadlines remain per-client.
        return new OneShotHttpClient(withTimeout(client.newBuilder(), timeout), false);
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
        RequestBody body = source == null ? null : new RequestBody() {
            @Override
            public MediaType contentType() {
                return source.contentType() == null ? null : MediaType.parse(source.contentType());
            }

            @Override
            public long contentLength() {
                return source.contentLength();
            }

            @Override
            public boolean isOneShot() {
                // Also disallow OkHttp status-code follow-ups such as HTTP 408/503.
                return true;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                source.writeTo(sink.outputStream());
            }
        };
        var builder = new Request.Builder().url(request.url()).method(request.method().name(), body);
        for (String name : request.headers().names()) {
            for (String value : request.headers().values(name)) {
                builder.addHeader(name, value);
            }
        }
        // Consume a bounded decoded body under OkHttp's call/read deadline, not a timed Future.
        // Synchronous platform DNS resolution is not proven to obey a hard call deadline.
        try (Response response = client.newCall(builder.build()).execute()) {
            int status = response.code();
            if (!response.isSuccessful()) {
                // Do not read, retain or let SDK error handling log a provider error body.
                throw new ModelCallException("MODEL_REQUEST_FAILED");
            }
            // OkHttp transparently decompresses gzip before exposing this stream.
            byte[] bytes = response.body() == null ? new byte[0]
                    : response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new ModelCallException("MODEL_RESPONSE_TOO_LARGE");
            }
            guardProtocol(bytes);
            Headers.Builder headers = Headers.builder();
            for (int i = 0; i < response.headers().size(); i++) {
                headers.put(response.headers().name(i), response.headers().value(i));
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
        } catch (IOException ex) {
            throw new OpenAIIoException("Model HTTP transport failed", ex);
        } finally {
            if (source != null) {
                source.close();
            }
        }
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

    @Override
    public void close() {
        if (ownsTransport) {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }
}
