package com.agent.agentcorelab.tool;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTests {
    private final Tool tool = new Tool() {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("query", "Read a bounded query", """
                    {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":10},
                    "limit":{"type":"integer","minimum":1,"maximum":3}},"required":["query"],"additionalProperties":false}
                    """);
        }

        @Override
        public ToolOutcome execute(String argumentsJson, ToolContext context) {
            throw new AssertionError("Validation must not execute a tool");
        }
    };
    private final ToolExecutor executor = new ToolExecutor(new ToolCatalog(List.of(tool)), 16384);

    @Test
    void validatesRequiredTypesRangesAndUnknownProperties() {
        assertNull(validate("{\"query\":\"test\",\"limit\":2}"));
        for (String json : List.of("{}", "[]", "{\"query\":3}", "{\"query\":\"x\",\"limit\":4}",
                "{\"query\":\"x\",\"limit\":1.5}", "{\"query\":\"x\",\"path\":\"../secrets\"}")) {
            assertEquals("INVALID_ARGUMENT", validate(json).code());
        }
    }

    @Test
    void rejectsDuplicateKeysTrailingJsonAndMalformedJson() {
        for (String json : List.of("{\"query\":\"x\",\"query\":\"y\"}", "{\"query\":\"x\"} {}", "{")) {
            assertEquals("INVALID_ARGUMENT", validate(json).code());
        }
    }

    @Test
    void blocksDeeplyNestedAndOversizedArguments() {
        assertEquals("INVALID_ARGUMENT", validate("[".repeat(40) + "0" + "]".repeat(40)).code());
        assertEquals("INVALID_ARGUMENT", validate("{\"query\":\"" + "x".repeat(70000) + "\"}").code());
    }

    @Test
    void canonicalizationSortsNestedKeysButPreservesArrayOrderAndValues() {
        ToolCall left = new ToolCall("a", "query", "{\"a\":{\"z\":1,\"b\":2},\"b\":[1,2]}");
        ToolCall right = new ToolCall("b", "query", "{ \"b\":[1,2],\"a\":{\"b\":2,\"z\":1}}");
        assertEquals(ToolExecutor.fingerprint(left), ToolExecutor.fingerprint(right));
        assertNotEquals(ToolExecutor.fingerprint(left), ToolExecutor.fingerprint(
                new ToolCall("c", "query", "{\"a\":{\"z\":1,\"b\":2},\"b\": [2,1]}")));
    }

    @Test
    void rejectsSchemaReferencesInsteadOfFetchingExternalResources() {
        Tool referenced = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("unsafe", "An invalid schema", """
                        {"type":"object","properties":{"query":{"$ref":"file:///secret"}}}
                        """);
            }

            @Override
            public ToolOutcome execute(String argumentsJson, ToolContext context) {
                throw new AssertionError();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new ToolExecutor(new ToolCatalog(List.of(referenced)), 16384));
    }

    @Test
    void numericValidationAndFingerprintsRetainDecimalPrecision() {
        assertEquals("INVALID_ARGUMENT", validate("{\"query\":\"x\",\"limit\":3.0000000000000001}").code());
        ToolCall integer = new ToolCall("a", "query", "{\"value\":9007199254740992.0}");
        ToolCall next = new ToolCall("b", "query", "{\"value\":9007199254740993.0}");
        assertNotEquals(ToolExecutor.fingerprint(integer), ToolExecutor.fingerprint(next));
    }

    @Test
    void bothSuccessAndFailureUseEncodedUtf8ByteLimits() {
        for (ToolOutcome outcome : List.of(new ToolOutcome.Success("{\"data\":\"" + "数".repeat(100) + "\"}"),
                new ToolOutcome.Failure("INVALID_ARGUMENT", "数".repeat(100), false),
                new ToolOutcome.Failure("INVALID_ARGUMENT", "\n".repeat(120), false))) {
            ToolOutcome.Failure failure = assertInstanceOf(ToolOutcome.Failure.class, execute(outcome));
            assertEquals("TOOL_RESULT_LIMIT", failure.code());
            assertTrue(failure.terminal());
        }
        ToolOutcome.Success success = new ToolOutcome.Success("{\"ok\":true}");
        assertSame(success, execute(success));
        ToolOutcome.Failure failure = new ToolOutcome.Failure("INVALID_ARGUMENT", "Correct the query", false);
        assertSame(failure, execute(failure));
    }

    @Test
    void invalidSuccessPayloadsExplainObjectWrappingWithoutLeakingOutput() {
        for (String json : new String[]{"[]", "\"private-tool-text\"", "private-tool-text", "{", "null", "{} {}", null}) {
            ToolOutcome.Failure failure = assertInstanceOf(ToolOutcome.Failure.class,
                    execute(new ToolOutcome.Success(json)));
            assertEquals("TOOL_PROTOCOL_ERROR", failure.code());
            assertTrue(failure.terminal());
            assertTrue(failure.message().contains("{\"data\":[]}"));
            assertTrue(failure.message().contains("{\"data\":\"text\"}"));
            assertFalse(failure.message().contains("private-tool-text"));
            String encoded = ToolJson.write(java.util.Map.of("ok", false, "error",
                    java.util.Map.of("code", failure.code(), "message", failure.message())));
            assertTrue(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256);
        }
    }

    @Test
    void wrappedArrayAndTextResultsAreAccepted() {
        for (String json : List.of("{\"data\":[]}", "{\"data\":\"text\"}")) {
            ToolOutcome.Success success = new ToolOutcome.Success(json);
            assertSame(success, execute(success));
        }
    }

    private ToolOutcome execute(ToolOutcome outcome) {
        Tool returning = new Tool() {
            @Override public ToolDefinition definition() { return tool.definition(); }
            @Override public ToolOutcome execute(String argumentsJson, ToolContext context) { return outcome; }
        };
        ToolExecutor bounded = new ToolExecutor(new ToolCatalog(List.of(returning)), 256);
        ToolContext context = new ToolContext(UUID.randomUUID(), null, Instant.now().plusSeconds(30),
                () -> false, artifact -> fail("No artifact expected"), null);
        return bounded.execute(new ToolCall("id", "query", "{\"query\":\"x\"}"), context);
    }

    private ToolOutcome.Failure validate(String json) {
        return executor.validate(new ToolCall("id", "query", json));
    }
}
