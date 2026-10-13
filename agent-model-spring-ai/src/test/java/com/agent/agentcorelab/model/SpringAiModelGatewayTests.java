package com.agent.agentcorelab.model;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SpringAiModelGatewayTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final ToolDefinition DEFINITION = new ToolDefinition("count", "统计中文资料",
            "{\"type\":\"object\",\"properties\":{\"城市\":{\"type\":\"string\"}},\"required\":[\"城市\"]}");
    private HttpServer server;
    private ExecutorService executor;
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> requests = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> remotePorts = new LinkedBlockingQueue<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);
    private String root;

    record Reply(int status, String body, boolean stallHeaders, boolean stallBody, boolean gzip) {
        Reply(int status, String body, boolean stallHeaders, boolean stallBody) {
            this(status, body, stallHeaders, stallBody, false);
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            remotePorts.add(exchange.getRemoteAddress().getPort());
            try (exchange) {
                requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                Reply reply = replies.poll();
                if (reply == null) {
                    reply = new Reply(500, "{}", false, false);
                }
                if (reply.stallHeaders()) {
                    release.await(10, TimeUnit.SECONDS);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.getResponseHeaders().set("Location", root + "/chat/completions");
                byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
                if (reply.gzip()) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    body = gzip(body);
                }
                exchange.sendResponseHeaders(reply.status(), body.length);
                if (reply.stallBody()) {
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    release.await(10, TimeUnit.SECONDS);
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // Expected when the client's real socket/call timeout cancels a stalled response.
            }
        });
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private SpringAiModelGateway gateway() {
        return new SpringAiModelGateway("local-test-key", root, "local-tool-model", 123);
    }

    private SpringAiModelGateway.Session session() {
        return (SpringAiModelGateway.Session) gateway().openSession("保留原始规则", "分析北京数据", List.of(DEFINITION));
    }

    private void reply(String body) {
        replies.add(new Reply(200, body, false, false));
    }

    private static byte[] gzip(byte[] body) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(body);
        }
        return bytes.toByteArray();
    }

    private static String completion(String message, String reason, String usage) {
        return "{\"id\":\"native-response-id\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"local-tool-model\",\"choices\":[{\"index\":0,\"message\":" + message
                + ",\"finish_reason\":\"" + reason + "\"}]" + usage + "}";
    }

    private static String call(String id) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"count\","
                + "\"arguments\":\"{\\\"城市\\\":\\\"北京\\\"}\"},"
                + "\"extra_content\":{\"google\":{\"thought_signature\":\"不透明签名\"}}}";
    }

    private static String toolMessage(String calls) {
        return "{\"role\":\"assistant\",\"content\":\"先读取资料\","
                + "\"reasoning_content\":\"供应商协议元数据\",\"tool_calls\":[" + calls + "]}";
    }

    @Test
    void realSdkMakesOneRequestAndDoesNotExecuteTools() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Tool countingTool = new Tool() {
            public ToolDefinition definition() { return DEFINITION; }
            public ToolOutcome execute(String json, ToolContext context) {
                executions.incrementAndGet();
                return new ToolOutcome.Success("{}");
            }
        };
        reply(completion(toolMessage(call("call-中文")), "tool_calls", ""));
        try (ModelSession session = gateway().openSession("规则", "分析中文", List.of(countingTool.definition()))) {
            ModelResponse response = session.nextResponse(TIMEOUT);
            assertEquals(1, requestCount.get());
            assertEquals(0, executions.get());
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());
            assertEquals("call-中文", response.toolCalls().getFirst().callId());
            assertEquals("北京", JSON.readTree(response.toolCalls().getFirst().argumentsJson()).get("城市").asText());
            assertTrue(response.usage().isEmpty(), "missing usage is unknown, not zero");
            JsonNode request = requests.remove();
            assertEquals(123, request.get("max_completion_tokens").asInt());
            assertEquals("local-tool-model", request.get("model").asText());
            assertEquals("count", request.at("/tools/0/function/name").asText());
            assertEquals("string", request.at("/tools/0/function/parameters/properties/城市/type").asText());
        }
    }

    @Test
    void preservesNativeAssistantAndPairsEveryResultByCallId() throws Exception {
        reply(completion(toolMessage(call("a") + "," + call("b")), "tool_calls", ""));
        reply(completion("{\"role\":\"assistant\",\"content\":\"已完成中文统计\"}", "stop",
                ",\"usage\":{\"prompt_tokens\":51,\"completion_tokens\":7,\"total_tokens\":58}"));
        try (var session = session()) {
            long initial = session.estimatedInputTokens();
            session.nextResponse(TIMEOUT);
            AssistantMessage nativeAssistant = (AssistantMessage) session.nativeMessages().get(2);
            assertEquals("供应商协议元数据", nativeAssistant.getMetadata().get("reasoningContent"));
            assertEquals("native-response-id", nativeAssistant.getMetadata().get("id"));
            assertTrue(session.estimatedInputTokens() > initial);
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
            assertThrows(IllegalStateException.class, () -> session.appendUserMessage("不能插入"));
            assertThrows(IllegalStateException.class, () -> session.appendToolResult("wrong", new ToolOutcome.Success("{}")));
            session.appendToolResult("b", new ToolOutcome.Failure("INVALID_ARGUMENT", "城市字段错误", false));
            assertThrows(IllegalStateException.class, () -> session.appendToolResult("b", new ToolOutcome.Success("{}")));
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
            session.appendToolResult("a", new ToolOutcome.Success("{\"数量\":12}"));
            ModelResponse response = session.nextResponse(TIMEOUT);
            assertSame(nativeAssistant, session.nativeMessages().get(2));
            assertEquals("已完成中文统计", response.text());
            assertEquals(new ModelResponse.TokenUsage(51, 7), response.usage().orElseThrow());
            requests.remove();
            JsonNode second = requests.remove();
            assertEquals("assistant", second.at("/messages/2/role").asText());
            assertEquals("供应商协议元数据", second.at("/messages/2/reasoning_content").asText());
            assertEquals("不透明签名", second.at("/messages/2/tool_calls/0/extra_content/google/thought_signature").asText());
            assertEquals("tool", second.at("/messages/3/role").asText());
            assertEquals("b", second.at("/messages/3/tool_call_id").asText());
            JsonNode failure = JSON.readTree(second.at("/messages/3/content").asText());
            assertFalse(failure.get("ok").asBoolean());
            assertEquals("INVALID_ARGUMENT", failure.at("/error/code").asText());
            assertFalse(failure.has("terminal"), "runtime control must not be model-authored");
            assertEquals("a", second.at("/messages/4/tool_call_id").asText());
            assertEquals("{\"数量\":12}", second.at("/messages/4/content").asText());
            assertEquals(2, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 307, 401, 408, 429, 500, 503})
    void errorsAndRedirectsNeverRetryAndDoNotLeakProviderBodies(int status) {
        replies.add(new Reply(status, "{\"error\":{\"message\":\"local-test-key private-provider-body\"}}", false, false));
        try (var session = session()) {
            ModelCallException error = assertThrows(ModelCallException.class, () -> session.nextResponse(TIMEOUT));
            assertEquals("MODEL_REQUEST_FAILED", error.code());
            assertNull(error.getCause());
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            assertFalse(stack.toString().contains("local-test-key"));
            assertFalse(stack.toString().contains("private-provider-body"));
            assertEquals(1, requestCount.get());
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        }
    }

    @Test
    void nonSuccessDoesNotWaitForOrRetainAStalledProviderErrorBody() {
        replies.add(new Reply(500, "{\"error\":{\"message\":\"private-stalled-error\"}}", false, true));
        try (var session = session()) {
            long start = System.nanoTime();
            ModelCallException error = assertThrows(ModelCallException.class,
                    () -> session.nextResponse(Duration.ofSeconds(3)));
            assertEquals("MODEL_REQUEST_FAILED", error.code());
            assertNull(error.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(1, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realTimeoutCoversBothHeadersAndResponseBody(boolean bodyStall) {
        replies.add(new Reply(200, completion("{\"role\":\"assistant\",\"content\":\"迟到\"}", "stop", ""),
                !bodyStall, bodyStall));
        try (var session = session()) {
            long start = System.nanoTime();
            ModelCallException error = assertThrows(ModelCallException.class,
                    () -> session.nextResponse(Duration.ofMillis(300)));
            assertEquals("MODEL_TIMEOUT", error.code());
            assertInstanceOf(HttpTimeoutException.class, error.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(1, requestCount.get());
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        }
    }

    @Test
    void stalledTlsHandshakeHasRealDeadlineWithoutDisablingTlsVerification() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch connected = new CountDownLatch(1);
            var accepted = executor.submit(() -> {
                try (var peer = socket.accept()) {
                    connected.countDown();
                    release.await(10, TimeUnit.SECONDS);
                }
                return null;
            });
            var gateway = new SpringAiModelGateway("local-test-key", "https://127.0.0.1:" + socket.getLocalPort() + "/v1", "model", 10);
            try (var session = gateway.openSession("规则", "目标", List.of())) {
                ModelCallException error = assertThrows(ModelCallException.class,
                        () -> session.nextResponse(Duration.ofMillis(300)));
                assertEquals("MODEL_TIMEOUT", error.code());
                assertTrue(connected.await(1, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                accepted.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void transportExplicitlyDisablesConnectionRetriesAndSetsEveryDeadline() {
        try (var transport = new OneShotHttpClient(Duration.ofMillis(271))) {
            assertFalse(transport.client().retryOnConnectionFailure());
            assertFalse(transport.client().followRedirects());
            assertFalse(transport.client().followSslRedirects());
            assertEquals(271, transport.client().connectTimeoutMillis());
            assertEquals(271, transport.client().readTimeoutMillis());
            assertEquals(271, transport.client().writeTimeoutMillis());
            assertEquals(271, transport.client().callTimeoutMillis());
        }
    }

    @Test
    void derivedClientsShareSessionResourcesWithoutMutatingDeadlinesOrOwningCleanup() {
        try (var owner = new OneShotHttpClient(Duration.ofMillis(271));
             var independent = new OneShotHttpClient(Duration.ofMillis(997))) {
            var dispatcherExecutor = owner.client().dispatcher().executorService();
            try (var shortCall = owner.withTimeout(Duration.ofMillis(31));
                 var longCall = owner.withTimeout(Duration.ofSeconds(2))) {
                assertNotSame(owner.client(), shortCall.client());
                assertNotSame(shortCall.client(), longCall.client());
                for (var call : List.of(shortCall, longCall)) {
                    assertSame(owner.client().connectionPool(), call.client().connectionPool());
                    assertSame(owner.client().dispatcher(), call.client().dispatcher());
                    assertFalse(call.client().retryOnConnectionFailure());
                    assertFalse(call.client().followRedirects());
                    assertFalse(call.client().followSslRedirects());
                }
                assertDeadlines(shortCall, 31);
                assertDeadlines(longCall, 2000);
                assertDeadlines(owner, 271);
                assertDeadlines(independent, 997);
                assertNotSame(owner.client().connectionPool(), independent.client().connectionPool());
                assertNotSame(owner.client().dispatcher(), independent.client().dispatcher());
            }
            assertFalse(dispatcherExecutor.isShutdown(), "per-call close must not shut down the session");
            assertDeadlines(owner, 271);
        }
    }

    private static void assertDeadlines(OneShotHttpClient transport, int millis) {
        assertEquals(millis, transport.client().connectTimeoutMillis());
        assertEquals(millis, transport.client().readTimeoutMillis());
        assertEquals(millis, transport.client().writeTimeoutMillis());
        assertEquals(millis, transport.client().callTimeoutMillis());
    }

    private static OneShotHttpClient sessionTransport(SpringAiModelGateway.Session session) throws Exception {
        var field = SpringAiModelGateway.Session.class.getDeclaredField("transport");
        field.setAccessible(true);
        return (OneShotHttpClient) field.get(session);
    }

    @Test
    void realRequestsReuseTcpConnectionAcrossToolStepsAndWaitingStyleResume() {
        reply(completion(toolMessage(call("a")), "tool_calls", ""));
        reply(completion("{\"role\":\"assistant\",\"content\":\"请补充资料\"}", "stop", ""));
        reply(completion("{\"role\":\"assistant\",\"content\":\"完成\"}", "stop", ""));
        try (var session = session()) {
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, session.nextResponse(TIMEOUT).finishReason());
            int port = remotePorts.remove();
            session.estimatedInputTokens();
            session.appendToolResult("a", new ToolOutcome.Success("{\"数量\":12}"));
            assertEquals("请补充资料", session.nextResponse(Duration.ofSeconds(3)).text());
            assertEquals(port, remotePorts.remove().intValue());
            // WAITING retains the session; externally supplied input resumes it without reconnecting.
            session.appendUserMessage("补充资料");
            session.appendRuntimeFeedback("请验证证据");
            assertEquals("完成", session.nextResponse(Duration.ofSeconds(2)).text());
            assertEquals(port, remotePorts.remove().intValue());
            assertEquals(3, requestCount.get());
        }
    }

    @Test
    void sessionCloseClearsHistoryAndReleasesOnlyItsOwnPoolAndDispatcher() throws Exception {
        var gateway = gateway();
        try (var first = (SpringAiModelGateway.Session) gateway.openSession("规则", "第一轮", List.of(DEFINITION));
             var second = (SpringAiModelGateway.Session) gateway.openSession("规则", "第二轮", List.of(DEFINITION))) {
            var firstTransport = sessionTransport(first);
            var secondTransport = sessionTransport(second);
            var firstPool = firstTransport.client().connectionPool();
            var secondPool = secondTransport.client().connectionPool();
            assertNotSame(firstPool, secondPool);
            assertNotSame(firstTransport.client().dispatcher(), secondTransport.client().dispatcher());
            reply(completion(toolMessage(call("pending")), "tool_calls", ""));
            first.nextResponse(TIMEOUT);
            int firstPort = remotePorts.remove();
            reply(completion("{\"role\":\"assistant\",\"content\":\"等待输入\"}", "stop", ""));
            second.nextResponse(TIMEOUT);
            int secondPort = remotePorts.remove();
            assertNotEquals(firstPort, secondPort);
            assertEquals(1, firstPool.idleConnectionCount());
            assertEquals(1, secondPool.idleConnectionCount());

            first.close();
            first.close();
            assertTrue(first.nativeMessages().isEmpty());
            assertEquals(0, firstPool.connectionCount());
            assertTrue(firstTransport.client().dispatcher().executorService().isShutdown());
            assertThrows(IllegalStateException.class, () -> first.nextResponse(TIMEOUT));
            assertThrows(IllegalStateException.class,
                    () -> first.appendToolResult("pending", new ToolOutcome.Success("{}")));
            assertEquals(1, secondPool.idleConnectionCount());
            assertFalse(secondTransport.client().dispatcher().executorService().isShutdown());

            second.appendUserMessage("恢复等待中的会话");
            reply(completion("{\"role\":\"assistant\",\"content\":\"继续完成\"}", "stop", ""));
            assertEquals("继续完成", second.nextResponse(Duration.ofSeconds(2)).text());
            assertEquals(secondPort, remotePorts.remove().intValue());
            second.close();
            assertTrue(second.nativeMessages().isEmpty());
            assertEquals(0, secondPool.connectionCount());
            assertTrue(secondTransport.client().dispatcher().executorService().isShutdown());
            assertEquals(3, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shorterPerCallTimeoutIsEnforcedOnReusedConnection(boolean bodyStall) {
        reply(completion("{\"role\":\"assistant\",\"content\":\"继续\"}", "stop", ""));
        replies.add(new Reply(200, completion("{\"role\":\"assistant\",\"content\":\"迟到\"}", "stop", ""),
                !bodyStall, bodyStall));
        try (var session = session()) {
            session.nextResponse(TIMEOUT);
            int port = remotePorts.remove();
            session.appendUserMessage("下一步");
            long start = System.nanoTime();
            ModelCallException error = assertThrows(ModelCallException.class,
                    () -> session.nextResponse(Duration.ofMillis(300)));
            assertEquals("MODEL_TIMEOUT", error.code());
            assertInstanceOf(HttpTimeoutException.class, error.getCause());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
            assertEquals(port, remotePorts.remove().intValue());
            assertEquals(2, requestCount.get());
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter", "unknown_reason"})
    void nonSuccessfulFinishReasonsAreNotNormalToolCalls(String reason) {
        reply(completion(toolMessage(call("a")), reason, ""));
        try (var session = session()) {
            ModelResponse response = session.nextResponse(TIMEOUT);
            assertEquals(switch (reason) {
                case "length" -> ModelResponse.FinishReason.LENGTH;
                case "content_filter" -> ModelResponse.FinishReason.REFUSED;
                default -> ModelResponse.FinishReason.ERROR;
            }, response.finishReason());
            assertThrows(IllegalStateException.class, () -> session.appendToolResult("a", new ToolOutcome.Success("{}")));
        }
    }

    @Test
    void refusalAndDuplicateOrBlankCallIdsFailClosed() {
        for (String message : List.of(toolMessage(call("same") + "," + call("same")), toolMessage(call("")),
                "{\"role\":\"assistant\",\"content\":\"\",\"refusal\":\"拒绝\"}")) {
            reply(completion(message, message.contains("refusal") ? "stop" : "tool_calls", ""));
            try (var session = session()) {
                ModelResponse response = session.nextResponse(TIMEOUT);
                assertTrue(response.finishReason() == ModelResponse.FinishReason.ERROR
                        || response.finishReason() == ModelResponse.FinishReason.REFUSED);
                assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "[]", "null", "{\"choices\":null}",
            "{\"choices\":{}}", "{\"choices\":[null]}", "{\"choices\":[{}]}", "{\"choices\":[]}",
            "{\"id\":\"x\",\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"count\",\"arguments\":\"{}\"}}]}}]}"})
    void malformedProtocolFailsWithoutRetry(String body) {
        assertProtocolRejected(body);
        assertEquals(1, requestCount.get());
    }

    private void assertProtocolRejected(String body) {
        reply(body);
        try (var session = session()) {
            ModelCallException error = assertThrows(ModelCallException.class, () -> session.nextResponse(TIMEOUT));
            assertEquals("MODEL_PROTOCOL_ERROR", error.code());
            assertNull(error.getCause(), "raw parser failures must not retain provider content");
            assertEquals(2, session.nativeMessages().size(), "invalid responses must never enter history");
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        }
    }

    @ParameterizedTest
    @CsvSource({"false,-1", "false,0", "false,1", "true,-1", "true,0", "true,1"})
    void decodedResponseLimitIncludesGzipAndAcceptsExactBoundary(boolean compressed, int excess) throws Exception {
        String base = completion("{\"role\":\"assistant\",\"content\":\"完成\"}", "stop", "");
        String body = base + " ".repeat(OneShotHttpClient.MAX_RESPONSE_BYTES + excess
                - base.getBytes(StandardCharsets.UTF_8).length);
        if (compressed) {
            assertTrue(gzip(body.getBytes(StandardCharsets.UTF_8)).length < OneShotHttpClient.MAX_RESPONSE_BYTES / 100);
        }
        replies.add(new Reply(200, body, false, false, compressed));
        try (var session = session()) {
            if (excess > 0) {
                ModelCallException error = assertThrows(ModelCallException.class, () -> session.nextResponse(TIMEOUT));
                assertEquals("MODEL_RESPONSE_TOO_LARGE", error.code());
                assertNull(error.getCause());
                assertEquals(2, session.nativeMessages().size());
                assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
            } else {
                assertEquals("完成", session.nextResponse(TIMEOUT).text());
            }
            assertEquals(1, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedDecodedStreamIsRejectedBeforeEofOrGzipTrailer(boolean compressed) {
        server.createContext("/v1/oversized/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (compressed) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                }
                exchange.sendResponseHeaders(200, 0);
                try (var output = compressed ? new GZIPOutputStream(exchange.getResponseBody(), true)
                        : exchange.getResponseBody()) {
                    output.write(" ".repeat(OneShotHttpClient.MAX_RESPONSE_BYTES + 2).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    // No EOF (or gzip trailer) until after the client has rejected and closed the response.
                    release.await(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The bounded client closes without waiting for the rest of this response.
            }
        });
        try (var session = new SpringAiModelGateway("local-test-key", root + "/oversized", "model", 123)
                .openSession("rules", "goal", List.of())) {
            long start = System.nanoTime();
            ModelCallException error = assertThrows(ModelCallException.class,
                    () -> session.nextResponse(Duration.ofSeconds(3)));
            assertEquals("MODEL_RESPONSE_TOO_LARGE", error.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(1, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"not-an-array\"", "17", "[null]", "[{}]"})
    void toolCallsMustBeAnArrayOfFunctionCalls(String calls) {
        assertProtocolRejected(completion("{\"role\":\"assistant\",\"content\":\"text\",\"tool_calls\":" + calls + "}",
                "tool_calls", ""));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @CsvSource({"custom,false", "custom,true", "future_kind,false", "future_kind,true"})
    void unsupportedToolTypesRejectTheWholeResponseIncludingMixedLists(String type, boolean mixed) {
        String unsupported = "{\"id\":\"other\",\"type\":\"" + type + "\","
                + "\"custom\":{\"name\":\"count\",\"input\":\"private-input\"}}";
        assertProtocolRejected(completion(toolMessage((mixed ? call("valid") + "," : "") + unsupported), "tool_calls", ""));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"user\",\"content\":\"text\"}",
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"text\"}]}",
            "{\"role\":\"assistant\",\"content\":{},\"tool_calls\":[]}",
            "{\"role\":\"assistant\",\"content\":\"text\",\"audio\":{}}",
            "{\"role\":\"assistant\",\"content\":\"text\",\"function_call\":{}}"})
    void onlySupportedTextAndFunctionMessagesAreAccepted(String message) {
        assertProtocolRejected(completion(message, "stop", ""));
    }

    @Test
    void duplicateKeysTrailingDataAndDeepExtensionsAreRejectedBeforeSdkMapping() {
        String valid = completion("{\"role\":\"assistant\",\"content\":\"text\"}", "stop", "");
        List<String> malformed = List.of(
                valid + " {}", valid + " private-trailing-data",
                valid.replace("\"id\":", "\"id\":\"private-overwritten-id\",\"id\":"),
                valid.replace("\"content\":", "\"content\":\"private-overwritten-content\",\"content\":"),
                completion(toolMessage(call("a").replace("\"type\":\"function\"",
                        "\"type\":\"custom\",\"type\":\"function\"")), "tool_calls", ""),
                completion("{\"role\":\"assistant\",\"content\":\"text\"}", "stop",
                        ",\"extension\":" + "[".repeat(OneShotHttpClient.MAX_JSON_DEPTH + 1)
                                + "0" + "]".repeat(OneShotHttpClient.MAX_JSON_DEPTH + 1)));
        malformed.forEach(this::assertProtocolRejected);
        assertEquals(malformed.size(), requestCount.get());
    }

    @Test
    void invalidChoiceIndexesAndFinishReasonShapesFailClosed() {
        String valid = completion("{\"role\":\"assistant\",\"content\":\"text\"}", "stop", "");
        List<String> malformed = List.of(valid.replace("\"index\":0", "\"index\":1"),
                valid.replace("\"index\":0", "\"index\":4294967296"),
                valid.replace("\"index\":0", "\"index\":\"0\""),
                valid.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":null"),
                valid.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":{}"));
        malformed.forEach(this::assertProtocolRejected);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    @ExtendWith(OutputCaptureExtension.class)
    @ResourceLock("SYSTEM_OUT")
    @ResourceLock("SYSTEM_ERR")
    @ResourceLock("MODEL_LOGGING")
    void invalidChoiceCountsNeverLeakPromptsOrBodiesToLogsOrStderr(int count, CapturedOutput output) {
        String instructions = "sensitive-instructions-739c";
        String goal = "sensitive-goal-19ee";
        String toolDescription = "sensitive-tool-description-2e71";
        String key = "sensitive-api-key-70aa";
        String provider = "sensitive-provider-content-544d";
        String choice = "{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + provider + "\"}}";
        reply("{\"id\":\"local-response\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"local-model\","
                + "\"choices\":[" + (count == 0 ? "" : choice + "," + choice) + "],\"provider_detail\":\"" + provider + "\"}");
        Logger modelLogger = (Logger) LoggerFactory.getLogger(OpenAiChatModel.class);
        Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = modelLogger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        rootLogger.addAppender(appender);
        // Prove the raw guard works even without the application's additional logger OFF setting.
        modelLogger.setLevel(Level.ALL);
        try (var session = new SpringAiModelGateway(key, root, "local-model", 123)
                .openSession(instructions, goal, List.of(new ToolDefinition("count", toolDescription, "{\"type\":\"object\"}")))) {
            ModelCallException error = assertThrows(ModelCallException.class, () -> session.nextResponse(TIMEOUT));
            assertEquals("MODEL_PROTOCOL_ERROR", error.code());
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            for (String marker : List.of(instructions, goal, toolDescription, key, provider)) {
                assertFalse(logs.contains(marker), "sensitive marker reached a logger");
                assertFalse(output.getOut().contains(marker), "sensitive marker reached stdout");
                assertFalse(output.getErr().contains(marker), "sensitive marker reached stderr");
                assertFalse(stack.toString().contains(marker), "sensitive marker reached exception causes");
            }
            assertFalse(logs.contains("No choices returned for prompt"));
            assertEquals(1, requestCount.get());
        } finally {
            modelLogger.setLevel(previousLevel);
            rootLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://api.example.test/v1", "http://192.168.1.1/v1", "http://127.0.0.2/v1",
            "http://127.1/v1", "http://2130706433/v1", "http://localhost.example.test/v1",
            "http://localhost./v1", "http://[::ffff:127.0.0.1]/v1", "ftp://localhost/v1",
            "https://user:private-url-key@example.test/v1", "https://example.test/v1?key=private-url-key",
            "https://example.test/v1#private-url-key"})
    void remotePlaintextAndAmbiguousUrlsAreRejectedWithoutEchoingInput(String url) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SpringAiModelGateway("private-constructor-key", url, "model", 1));
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains(url));
        assertFalse(stack.toString().contains("private-url-key"));
        assertFalse(stack.toString().contains("private-constructor-key"));
        assertEquals(0, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.test/v1", "https://127.0.0.1/v1", "http://127.0.0.1:12345/v1",
            "http://localhost:12345/v1", "http://LOCALHOST:12345/v1", "http://[::1]:12345/v1"})
    void httpsAndOnlyLiteralLoopbackHttpRootsAreAcceptedWithoutConnecting(String url) {
        assertDoesNotThrow(() -> new SpringAiModelGateway("local-test-key", url, "model", 1));
        assertEquals(0, requestCount.get());
    }

    @Test
    void estimateIncludesSchemasChineseAndNativeMetadataAndDoesNotReserveOutput() {
        try (var withTools = session(); var withoutTools = gateway().openSession("保留原始规则", "分析北京数据", List.of())) {
            assertTrue(withTools.estimatedInputTokens() - withoutTools.estimatedInputTokens()
                    >= DEFINITION.inputSchema().getBytes(StandardCharsets.UTF_8).length);
            long previous = withTools.estimatedInputTokens();
            withTools.appendUserMessage("追加中文");
            withTools.appendRuntimeFeedback("缺少证据");
            assertTrue(withTools.estimatedInputTokens() > previous);
            assertEquals(2, ((SpringAiModelGateway.Session) withoutTools).nativeMessages().size());
        }
        assertEquals(0, requestCount.get());
    }

    @Test
    void conservativeEstimatorCountsOnlyUtf8BytesWithoutFraming() {
        TokenEstimator estimator = TokenEstimator.conservativeUtf8();
        for (String context : List.of("", "ASCII", "中文资料", "supplementary \uD801\uDC37")) {
            assertEquals(context.getBytes(StandardCharsets.UTF_8).length, estimator.estimate("any-model", context));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultEstimateExactlyMatchesLegacySerializationAndFraming(boolean explicitEstimator) throws Exception {
        var gateway = explicitEstimator
                ? new SpringAiModelGateway("local-test-key", root, "local-tool-model", 123, TokenEstimator.conservativeUtf8())
                : gateway();
        reply(completion(toolMessage(call("call-中文")), "tool_calls", ""));
        try (var session = (SpringAiModelGateway.Session) gateway.openSession("保留原始规则", "分析北京数据", List.of(DEFINITION));
             var withoutTools = (SpringAiModelGateway.Session) gateway.openSession("保留原始规则", "分析北京数据", List.of())) {
            assertLegacyEstimate(session, List.of(DEFINITION));
            assertLegacyEstimate(withoutTools, List.of());
            session.appendUserMessage("补充文字 \uD801\uDC37");
            session.appendRuntimeFeedback("缺少证据");
            assertLegacyEstimate(session, List.of(DEFINITION));
            session.nextResponse(TIMEOUT);
            assertLegacyEstimate(session, List.of(DEFINITION));
            session.appendToolResult("call-中文", new ToolOutcome.Success("{\"数量\":12}"));
            assertLegacyEstimate(session, List.of(DEFINITION));
        }
    }

    private static byte[] serializedContext(SpringAiModelGateway.Session session, List<ToolDefinition> tools) throws Exception {
        return JSON.writeValueAsBytes(Map.of("messages", session.nativeMessages(), "tools", tools));
    }

    private static void assertLegacyEstimate(SpringAiModelGateway.Session session, List<ToolDefinition> tools) throws Exception {
        long legacy = serializedContext(session, tools).length + 32L * (session.nativeMessages().size() + tools.size() + 1);
        assertEquals(legacy, session.estimatedInputTokens());
    }

    @Test
    void customEstimatorReceivesModelAndImmutableFullContextIncludingToolsResultsAndMetadata() throws Exception {
        var models = new ArrayList<String>();
        var contexts = new ArrayList<String>();
        TokenEstimator estimator = (model, contextJson) -> {
            models.add(model);
            contexts.add(contextJson);
            return 17;
        };
        var gateway = new SpringAiModelGateway("local-test-key", root, "custom-token-model", 123, estimator);
        reply(completion(toolMessage(call("call-中文") + "," + call("failure-call")), "tool_calls", ""));
        try (var session = (SpringAiModelGateway.Session) gateway.openSession("保留原始规则", "分析北京数据", List.of(DEFINITION))) {
            assertEquals(17 + 32L * (2 + 1 + 1), session.estimatedInputTokens());
            String initial = contexts.getFirst();
            assertEquals(new String(serializedContext(session, List.of(DEFINITION)), StandardCharsets.UTF_8), initial);
            JsonNode initialContext = JSON.readTree(initial);
            assertEquals(2, initialContext.path("messages").size());
            assertEquals(DEFINITION.inputSchema(), initialContext.at("/tools/0/inputSchema").asText());
            for (String text : List.of("保留原始规则", "分析北京数据", "统计中文资料", "城市")) {
                assertTrue(initial.contains(text));
            }

            session.nextResponse(TIMEOUT);
            session.appendToolResult("call-中文", new ToolOutcome.Success("{\"数量\":12}"));
            session.appendToolResult("failure-call", new ToolOutcome.Failure("INVALID_ARGUMENT", "城市字段错误", false));
            session.appendUserMessage("追加中文");
            session.appendRuntimeFeedback("缺少证据");
            assertEquals(17 + 32L * (7 + 1 + 1), session.estimatedInputTokens());
            String full = contexts.getLast();
            assertEquals(new String(serializedContext(session, List.of(DEFINITION)), StandardCharsets.UTF_8), full);
            assertEquals("供应商协议元数据", JSON.readTree(full).at("/messages/2/metadata/reasoningContent").asText());
            for (String text : List.of("call-中文", "failure-call", "北京", "数量", "INVALID_ARGUMENT", "城市字段错误",
                    "native-response-id", "不透明签名", "追加中文", "Runtime completion-check feedback:", "缺少证据")) {
                assertTrue(full.contains(text), "estimator lost retained context: " + text);
            }
            assertEquals(initial, contexts.getFirst());
            assertEquals(2, JSON.readTree(initial).path("messages").size());
            assertFalse(initial.contains("追加中文"));
            assertEquals(List.of("custom-token-model", "custom-token-model"), models);
            assertEquals(2, contexts.size());
            assertEquals(1, requestCount.get());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 127})
    void invalidOrOverflowingEstimatorResultsFailSafely(long estimate) {
        var gateway = new SpringAiModelGateway("local-test-key", root, "private-estimator-model", 123,
                (model, contextJson) -> estimate);
        try (var session = gateway.openSession("private-estimator-instructions", "private-estimator-goal", List.of(DEFINITION))) {
            assertSafeEstimateFailure(session);
        }
        assertEquals(0, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, Long.MAX_VALUE - 128})
    void positiveEstimatorResultsAcceptExactFramingBoundary(long estimate) {
        var gateway = new SpringAiModelGateway("local-test-key", root, "local-tool-model", 123,
                (model, contextJson) -> estimate);
        try (var session = gateway.openSession("规则", "目标", List.of(DEFINITION))) {
            assertEquals(estimate + 128, session.estimatedInputTokens());
        }
        assertEquals(0, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void estimatorExceptionsNeverExposeContextOrOriginalCause(boolean modelException) {
        TokenEstimator estimator = (model, contextJson) -> {
            String detail = "private-estimator-error " + model + " " + contextJson;
            if (modelException) {
                throw new ModelCallException(detail);
            }
            throw new IllegalStateException(detail, new IllegalArgumentException("private-estimator-cause"));
        };
        var gateway = new SpringAiModelGateway("local-test-key", root, "private-estimator-model", 123, estimator);
        try (var session = gateway.openSession("private-estimator-instructions", "private-estimator-goal", List.of(DEFINITION))) {
            assertSafeEstimateFailure(session);
        }
        assertEquals(0, requestCount.get());
    }

    private static void assertSafeEstimateFailure(ModelSession session) {
        ModelCallException error = assertThrows(ModelCallException.class, session::estimatedInputTokens);
        assertEquals("MODEL_CONTEXT_ESTIMATE_FAILED", error.code());
        assertEquals("MODEL_CONTEXT_ESTIMATE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        for (String secret : List.of("private-estimator-", "local-test-key", DEFINITION.description(), DEFINITION.inputSchema())) {
            assertFalse(stack.toString().contains(secret));
        }
    }

    @Test
    void nullEstimatorIsRejectedBeforeOpeningAnySession() {
        assertThrows(NullPointerException.class,
                () -> new SpringAiModelGateway("local-test-key", root, "local-tool-model", 123, null));
        assertEquals(0, requestCount.get());
    }

    @Test
    void explicitZeroUsageIsKnownAndEmptyTextIsNotFabricated() {
        reply(completion("{\"role\":\"assistant\",\"content\":\"\"}", "stop",
                ",\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"));
        try (var session = session()) {
            ModelResponse response = session.nextResponse(TIMEOUT);
            assertEquals("", response.text());
            assertEquals(new ModelResponse.TokenUsage(0, 0), response.usage().orElseThrow());
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        }
    }

    @Test
    void terminalToolOutcomeForbidsAnotherModelRequest() {
        reply(completion(toolMessage(call("a")), "tool_calls", ""));
        try (var session = session()) {
            session.nextResponse(TIMEOUT);
            session.appendToolResult("a", new ToolOutcome.Failure("INPUT_CHANGED", "资料已变化", true));
            assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
            assertEquals(1, requestCount.get());
        }
    }

    @Test
    void closedSessionsAndSubMillisecondTimeoutsNeverSendRequests() {
        var session = session();
        assertThrows(IllegalArgumentException.class, () -> session.nextResponse(Duration.ofNanos(1)));
        session.close();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.nextResponse(TIMEOUT));
        assertThrows(IllegalStateException.class, session::estimatedInputTokens);
        assertEquals(0, requestCount.get());
    }

    @Test
    void nextResponseStreamingListenerEmitsChunks() {
        reply(completion("{\"role\":\"assistant\",\"content\":\"text response from model\"}", "stop", ""));
        try (var session = session()) {
            List<ModelStreamChunk> chunks = new ArrayList<>();
            ModelResponse response = session.nextResponse(TIMEOUT, chunks::add);
            assertEquals("text response from model", response.text());
            assertFalse(chunks.isEmpty());
            assertEquals("text response from model", chunks.get(0).text());
        }
    }

    @Test
    void compactContextReducesHistoricalToolResponseSize() {
        try (var session = session()) {
            reply(completion(toolMessage(call("c1")), "tool_calls", ""));
            session.nextResponse(TIMEOUT);
            // Append a large tool response (> 200 chars)
            StringBuilder bigData = new StringBuilder("{\"ok\":true,\"data\":[");
            for (int i = 0; i < 50; i++) {
                bigData.append("{\"item\":\"record_").append(i).append("\"},");
            }
            bigData.append("{\"item\":\"last\"}]}");
            session.appendToolResult("c1", new ToolOutcome.Success(bigData.toString()));

            // Now append a couple more message pairs so c1 is outside the keepRecent bound
            reply(completion(toolMessage(call("c2")), "tool_calls", ""));
            session.nextResponse(TIMEOUT);
            session.appendToolResult("c2", new ToolOutcome.Success("{\"ok\":true}"));

            reply(completion(toolMessage(call("c3")), "tool_calls", ""));
            session.nextResponse(TIMEOUT);
            session.appendToolResult("c3", new ToolOutcome.Success("{\"ok\":true}"));

            reply(completion(toolMessage(call("c4")), "tool_calls", ""));
            session.nextResponse(TIMEOUT);
            session.appendToolResult("c4", new ToolOutcome.Success("{\"ok\":true}"));

            long tokensBefore = session.estimatedInputTokens();
            boolean compacted = session.compactContext(tokensBefore - 50);
            long tokensAfter = session.estimatedInputTokens();

            assertTrue(compacted);
            assertTrue(tokensAfter < tokensBefore, "Tokens should decrease after compacting large earlier tool response");
        }
    }

    @Test
    void realTimeSseStreamingDispatchesThinkingAndTextChunksInFlightBeforeStreamCompletes() throws Exception {
        CountDownLatch chunk1Received = new CountDownLatch(1);
        CountDownLatch chunk2Received = new CountDownLatch(1);

        server.createContext("/v1/sse/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            try (exchange) {
                JsonNode req = JSON.readTree(exchange.getRequestBody().readAllBytes());
                requests.add(req);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                var out = exchange.getResponseBody();

                out.write(("data: {\"id\":\"sse-1\",\"model\":\"local-tool-model\",\"created\":1,"
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"先分析问题\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertTrue(chunk1Received.await(3, TimeUnit.SECONDS), "First SSE chunk must be delivered in-flight before second chunk is sent");

                out.write(("data: {\"id\":\"sse-1\",\"model\":\"local-tool-model\",\"created\":1,"
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"结论A\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                assertTrue(chunk2Received.await(3, TimeUnit.SECONDS), "Second SSE chunk must be delivered in-flight before final chunk is sent");

                out.write(("data: {\"id\":\"sse-1\",\"model\":\"local-tool-model\",\"created\":1,"
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"与结论B\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"id\":\"sse-1\",\"model\":\"local-tool-model\",\"created\":1,\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":12,\"total_tokens\":42}}\n\n"
                        + "data: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });

        var gateway = new SpringAiModelGateway("local-test-key", root + "/sse", "local-tool-model", 123);
        try (var session = (SpringAiModelGateway.Session) gateway.openSession("规则", "目标", List.of(DEFINITION))) {
            List<ModelStreamChunk> chunks = new ArrayList<>();
            ModelResponse response = session.nextResponse(TIMEOUT, chunk -> {
                chunks.add(chunk);
                if (chunks.size() == 1) {
                    chunk1Received.countDown();
                } else if (chunks.size() == 2) {
                    chunk2Received.countDown();
                }
            });

            assertTrue(requests.remove().path("stream").asBoolean(), "Streaming call must set stream=true in request payload");
            assertEquals(List.of(
                    ModelStreamChunk.thinking("先分析问题"),
                    ModelStreamChunk.text("结论A"),
                    ModelStreamChunk.text("与结论B")
            ), chunks);
            assertEquals("结论A与结论B", response.text());
            assertEquals(ModelResponse.FinishReason.STOP, response.finishReason());
            assertEquals(new ModelResponse.TokenUsage(30, 12), response.usage().orElseThrow());
            AssistantMessage nativeAssistant = (AssistantMessage) session.nativeMessages().get(2);
            assertEquals("先分析问题", nativeAssistant.getMetadata().get("reasoningContent"));
        }
    }

    @Test
    void sseStreamingReassemblesFragmentedToolCallsAndPreservesThoughtSignatures() throws Exception {
        server.createContext("/v1/sse-tools/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                var out = exchange.getResponseBody();
                out.write(("data: {\"id\":\"sse-tool-1\",\"model\":\"local-tool-model\",\"created\":1,"
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"准备调用工具\","
                        + "\"tool_calls\":[{\"index\":0,\"id\":\"call-sse-1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"count\",\"arguments\":\"{\\\"城\"},"
                        + "\"extra_content\":{\"google\":{\"thought_signature\":\"签名-sse\"}}}]}}]}\n\n"
                        + "data: {\"id\":\"sse-tool-1\",\"model\":\"local-tool-model\",\"created\":1,"
                        + "\"choices\":[{\"index\":0,\"delta\":{"
                        + "\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"市\\\":\\\"上海\\\"}\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });

        var gateway = new SpringAiModelGateway("local-test-key", root + "/sse-tools", "local-tool-model", 123);
        try (var session = (SpringAiModelGateway.Session) gateway.openSession("规则", "目标", List.of(DEFINITION))) {
            List<ModelStreamChunk> chunks = new ArrayList<>();
            ModelResponse response = session.nextResponse(TIMEOUT, chunks::add);
            assertEquals(List.of(ModelStreamChunk.text("准备调用工具")), chunks);
            assertEquals(ModelResponse.FinishReason.TOOL_CALLS, response.finishReason());
            assertEquals(1, response.toolCalls().size());
            assertEquals("call-sse-1", response.toolCalls().getFirst().callId());
            assertEquals("count", response.toolCalls().getFirst().name());
            assertEquals("{\"城市\":\"上海\"}", response.toolCalls().getFirst().argumentsJson());
        }
    }
}
