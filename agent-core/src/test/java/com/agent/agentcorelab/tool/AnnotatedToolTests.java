package com.agent.agentcorelab.tool;

import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.scenario.ScenarioPlugin;
import com.agent.agentcorelab.scenario.ScenarioRegistry;
import com.agent.agentcorelab.tool.annotation.AgentTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedTool;
import com.agent.agentcorelab.tool.annotation.AnnotatedToolScanner;
import com.agent.agentcorelab.tool.annotation.ToolParam;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class AnnotatedToolTests {

    private ToolContext context;

    @BeforeEach
    void setUp() {
        context = new ToolContext(UUID.randomUUID(), null, Instant.now().plusSeconds(60),
                () -> false, artifact -> fail("No artifact expected"), null);
    }

    public enum RoomType {
        STANDARD, DELUXE, SUITE
    }

    public record BookingResult(String bookingId, double totalPrice, boolean confirmed) {}

    public static class SampleHotelService {

        @AgentTool(name = "book_room", description = "Book a hotel room for a guest")
        public BookingResult bookRoom(
                @ToolParam(name = "guestName", description = "Name of the primary guest") String guestName,
                @ToolParam(description = "Number of nights", defaultValue = "1") int nights,
                @ToolParam(description = "Type of room") RoomType roomType,
                @ToolParam(description = "VIP guest flag", required = false) boolean isVip,
                @ToolParam(description = "Special requests", required = false) List<String> requests,
                ToolContext ctx
        ) {
            assertNotNull(ctx);
            double baseRate = switch (roomType) {
                case STANDARD -> 300.0;
                case DELUXE -> 500.0;
                case SUITE -> 1000.0;
            };
            double total = baseRate * nights;
            if (isVip) {
                total *= 0.8;
            }
            return new BookingResult("BK-" + guestName.toUpperCase(), total, true);
        }

        @AgentTool(name = "cancel_with_refund", description = "Cancel booking and issue refund", riskLevel = RiskLevel.HIGH_RISK)
        public String cancelWithRefund(@ToolParam(description = "Booking ID") String bookingId) {
            if ("BK-INVALID".equals(bookingId)) {
                throw new IllegalArgumentException("Booking ID does not exist");
            }
            return "{\"refunded\":true,\"bookingId\":\"" + bookingId + "\"}";
        }

        @AgentTool(description = "Clean room request")
        public void requestCleaning(@ToolParam(description = "Room number") int roomNumber) {
            // void method
        }

        @AgentTool(description = "Echo note")
        public String echoNote(@ToolParam(description = "Note text") String text) {
            return text;
        }
    }

    @Test
    void testBasicAnnotatedToolCreationAndSchema() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        assertEquals(4, tools.size());

        Tool bookTool = tools.stream().filter(t -> t.definition().name().equals("book_room")).findFirst().orElseThrow();
        assertEquals("book_room", bookTool.definition().name());
        assertEquals("Book a hotel room for a guest", bookTool.definition().description());
        assertEquals(RiskLevel.READ_ONLY, bookTool.definition().riskLevel());
        assertFalse(bookTool.definition().isHighRisk());

        // Validate JSON Schema
        String schemaJson = bookTool.definition().inputSchema();
        JsonNode schema = ToolJson.parse(schemaJson);
        assertEquals("object", schema.path("type").asText());
        assertEquals("false", schema.path("additionalProperties").asText());

        JsonNode properties = schema.path("properties");
        assertTrue(properties.has("guestName"));
        assertEquals("string", properties.path("guestName").path("type").asText());
        assertTrue(properties.has("nights"));
        assertEquals("integer", properties.path("nights").path("type").asText());
        assertTrue(properties.has("roomType"));
        assertEquals("string", properties.path("roomType").path("type").asText());
        assertEquals(3, properties.path("roomType").path("enum").size());
        assertTrue(properties.has("isVip"));
        assertEquals("boolean", properties.path("isVip").path("type").asText());
        assertTrue(properties.has("requests"));
        assertEquals("array", properties.path("requests").path("type").asText());
        // ToolContext must NOT be in JSON schema
        assertFalse(properties.has("ctx"));

        // ToolExecutor must compile this schema with zero errors
        ToolExecutor executor = new ToolExecutor(new ToolCatalog(List.of(bookTool)), 16384);
        assertNotNull(executor);
    }

    @Test
    void testExecutionWithFullArguments() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        Tool bookTool = tools.stream().filter(t -> t.definition().name().equals("book_room")).findFirst().orElseThrow();

        String args = """
                {
                  "guestName": "Alice",
                  "nights": 3,
                  "roomType": "SUITE",
                  "isVip": true,
                  "requests": ["lake view", "late checkout"]
                }
                """;

        ToolOutcome outcome = bookTool.execute(args, context);
        assertInstanceOf(ToolOutcome.Success.class, outcome);

        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        JsonNode result = ToolJson.parse(success.outputJson());
        assertEquals("BK-ALICE", result.path("bookingId").asText());
        assertEquals(2400.0, result.path("totalPrice").asDouble());
        assertTrue(result.path("confirmed").asBoolean());
    }

    @Test
    void testCaseInsensitiveEnumAndDefaultValueFallback() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        Tool bookTool = tools.stream().filter(t -> t.definition().name().equals("book_room")).findFirst().orElseThrow();

        // Omit nights (defaults to 1), pass lowercase enum "deluxe", omit requests and isVip
        String args = """
                {
                  "guestName": "Bob",
                  "roomType": "deluxe"
                }
                """;

        ToolOutcome outcome = bookTool.execute(args, context);
        assertInstanceOf(ToolOutcome.Success.class, outcome);

        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        JsonNode result = ToolJson.parse(success.outputJson());
        assertEquals("BK-BOB", result.path("bookingId").asText());
        assertEquals(500.0, result.path("totalPrice").asDouble()); // 1 night * 500
        assertTrue(result.path("confirmed").asBoolean());
    }

    @Test
    void testMissingRequiredParameterFailsGracefully() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        Tool bookTool = tools.stream().filter(t -> t.definition().name().equals("book_room")).findFirst().orElseThrow();

        // Missing guestName
        String args = """
                {
                  "nights": 2,
                  "roomType": "STANDARD"
                }
                """;

        ToolOutcome outcome = bookTool.execute(args, context);
        assertInstanceOf(ToolOutcome.Failure.class, outcome);
        ToolOutcome.Failure failure = (ToolOutcome.Failure) outcome;
        assertEquals("INVALID_ARGUMENT", failure.code());
        assertTrue(failure.message().contains("guestName"));
    }

    @Test
    void testHighRiskToolMetadataAndExecution() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        Tool cancelTool = tools.stream().filter(t -> t.definition().name().equals("cancel_with_refund")).findFirst().orElseThrow();

        assertEquals(RiskLevel.HIGH_RISK, cancelTool.definition().riskLevel());
        assertTrue(cancelTool.definition().isHighRisk());

        ToolOutcome outcome = cancelTool.execute("{\"bookingId\":\"BK-1002\"}", context);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        assertTrue(((ToolOutcome.Success) outcome).outputJson().contains("\"refunded\":true"));
    }

    @Test
    void testMethodExceptionHandling() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        Tool cancelTool = tools.stream().filter(t -> t.definition().name().equals("cancel_with_refund")).findFirst().orElseThrow();

        ToolOutcome outcome = cancelTool.execute("{\"bookingId\":\"BK-INVALID\"}", context);
        assertInstanceOf(ToolOutcome.Failure.class, outcome);
        ToolOutcome.Failure failure = (ToolOutcome.Failure) outcome;
        assertEquals("EXECUTION_ERROR", failure.code());
        assertTrue(failure.message().contains("Booking ID does not exist"));
    }

    @Test
    void testVoidAndPlainTextReturnHandling() {
        SampleHotelService service = new SampleHotelService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);

        Tool voidTool = tools.stream().filter(t -> t.definition().name().equals("requestCleaning")).findFirst().orElseThrow();
        ToolOutcome voidOutcome = voidTool.execute("{\"roomNumber\":808}", context);
        assertInstanceOf(ToolOutcome.Success.class, voidOutcome);
        assertEquals("{\"ok\":true}", ((ToolOutcome.Success) voidOutcome).outputJson());

        Tool echoTool = tools.stream().filter(t -> t.definition().name().equals("echoNote")).findFirst().orElseThrow();
        ToolOutcome echoOutcome = echoTool.execute("{\"text\":\"hello world\"}", context);
        assertInstanceOf(ToolOutcome.Success.class, echoOutcome);
        JsonNode echoNode = ToolJson.parse(((ToolOutcome.Success) echoOutcome).outputJson());
        assertEquals("hello world", echoNode.path("result").asText());
    }

    @Test
    void testScenarioPluginBuilderIntegration() {
        SampleHotelService service = new SampleHotelService();

        ScenarioPlugin plugin = ScenarioPlugin.builder("hotel-concierge")
                .description("智慧酒店综合服务管家")
                .instructions("你是一名经验丰富的酒店前台管家，为住客提供查询、订房、客房服务及退改签全流程支持。")
                .addAnnotatedTools(service)
                .build();

        assertEquals("hotel-concierge", plugin.id());
        assertEquals("智慧酒店综合服务管家", plugin.description());
        assertEquals(4, plugin.tools().size());

        RunLimits limits = new RunLimits(10, 10, java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(60));
        ScenarioRegistry registry = new ScenarioRegistry(List.of(plugin), limits);
        ScenarioRegistry.Entry entry = registry.require("hotel-concierge");
        assertNotNull(entry);
        assertEquals("hotel-concierge", entry.spec().id());
        assertTrue(entry.spec().toolNames().contains("book_room"));
        assertTrue(entry.spec().toolNames().contains("cancel_with_refund"));
        assertTrue(entry.spec().toolNames().contains("requestCleaning"));
        assertTrue(entry.spec().toolNames().contains("echoNote"));
        assertTrue(entry.spec().toolNames().contains("requestClarification"));
    }

    public static class DuplicateToolClass {
        @AgentTool(name = "duplicate_name", description = "One")
        public void toolOne() {}

        @AgentTool(name = "duplicate_name", description = "Two")
        public void toolTwo() {}
    }

    public record QueryArgs(
            @ToolParam(description = "Query SQL") String sql,
            @ToolParam(description = "Purpose of query") String purpose
    ) {}

    public static class RecordToolService {
        @AgentTool(name = "run_query", description = "Run query using record parameter")
        public String runQuery(QueryArgs args, ToolContext ctx) {
            assertNotNull(ctx);
            assertNotNull(args);
            return "{\"success\":true,\"sql\":\"" + args.sql() + "\",\"purpose\":\"" + args.purpose() + "\"}";
        }
    }

    @Test
    void testRecordParameterSchemaAndExecution() {
        RecordToolService service = new RecordToolService();
        List<Tool> tools = AnnotatedToolScanner.scan(service);
        assertEquals(1, tools.size());

        Tool tool = tools.getFirst();
        assertEquals("run_query", tool.definition().name());

        // Verify Schema contains flattened record fields: sql and purpose
        String schemaJson = tool.definition().inputSchema();
        JsonNode schema = ToolJson.parse(schemaJson);
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("properties").has("sql"));
        assertTrue(schema.path("properties").has("purpose"));
        assertEquals("string", schema.path("properties").path("sql").path("type").asText());
        assertEquals("string", schema.path("properties").path("purpose").path("type").asText());

        // Test execution with direct flattened JSON
        String args = """
                {
                  "sql": "SELECT 1",
                  "purpose": "test query"
                }
                """;
        ToolOutcome outcome = tool.execute(args, context);
        assertInstanceOf(ToolOutcome.Success.class, outcome);
        assertTrue(((ToolOutcome.Success) outcome).outputJson().contains("SELECT 1"));

        // Test execution with nested object under parameter name "args"
        String nestedArgs = """
                {
                  "args": {
                    "sql": "SELECT 2",
                    "purpose": "nested query"
                  }
                }
                """;
        ToolOutcome outcome2 = tool.execute(nestedArgs, context);
        assertInstanceOf(ToolOutcome.Success.class, outcome2);
        assertTrue(((ToolOutcome.Success) outcome2).outputJson().contains("SELECT 2"));
    }

    @Test
    void testDuplicateToolNameRejection() {
        assertThrows(IllegalArgumentException.class, () -> AnnotatedToolScanner.scan(new DuplicateToolClass()));
    }
}
