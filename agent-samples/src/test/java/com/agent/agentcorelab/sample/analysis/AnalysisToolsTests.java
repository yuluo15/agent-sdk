package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisToolsTests {
    private static final String HEADER = "date,channel,gross_sales,refund_amount,order_count\n";
    @TempDir Path root;

    @Test void fictionalFixtureComputesExactAmountsCountsAndAbsoluteRanking() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            run.discover();
            JsonNode baseline = run.success("inspectDataset", run.file("sales-baseline.csv"));
            assertEquals(6, baseline.path("data").path("rowCount").asInt());
            assertEquals(3, baseline.path("data").path("samples").size());
            JsonNode result = run.success("analyzeDataset", run.analysis());
            JsonNode data = result.get("data");
            assertGroup(data, "线上", "1100.00", "700.00", "-400.00", "-36.36", "12", "8");
            assertGroup(data, "门店", "900.00", "1100.00", "200.00", "22.22", "9", "11");
            assertGroup(data, "合作", "500.00", "600.00", "100.00", "20.00", "5", "6");
            assertEquals("线上", data.path("ranking").get(0).get("group").asText());
            assertEquals("-100.00", data.path("totals").path("delta").asText());
            assertEquals("2500.00", data.path("totals").path("before").path("net").asText());
            assertEquals("2400.00", data.path("totals").path("after").path("net").asText());
            assertEquals("26", data.path("totals").path("before").path("count").asText());
            assertEquals("25", data.path("totals").path("after").path("count").asText());
            assertEquals("-4.00", data.path("totals").path("percentChange").asText());
            assertEquals("100.00", group(data, "线上").path("before").path("deduction").asText());
            assertFalse(data.path("coverage").path("baseline").path("provesCompleteness").asBoolean());
            assertEquals(6, data.path("coverage").path("baseline").path("matchedRows").asInt());
            assertEquals("2026-08-01", result.path("sources").path("baseline").path("startDate").asText());
            assertTrue(result.path("sources").path("baseline").path("sha256").asText().matches("[0-9a-f]{64}"));
            assertFalse(WorkspaceJson.encode(result).contains(root.toString()));
        }
    }

    @Test void explicitRenamedColumnsWorkAndUnknownColumnsFail() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            Path current = run.workspace.inputRoot().resolve("sales-current.csv");
            Files.writeString(current, Files.readString(current).replace("refund_amount", "returns"));
            run.discover();
            assertEquals("UNKNOWN_COLUMN", run.failure("analyzeDataset", run.analysis()));
            ObjectNode arguments = run.analysis();
            ((ObjectNode) arguments.get("comparison")).put("deductionColumn", "returns");
            assertEquals("-100.00", run.success("analyzeDataset", arguments).path("data").path("totals").path("delta").asText());
        }
    }

    @Test void decimalArithmeticNeverUsesBinaryFloatingPoint() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,A,.1,0,1\n2026-08-02,A,+0.2,0,2\n");
            write(run, "sales-current.csv", HEADER + "2026-09-01,A,3E-1,0,3\n2026-09-02,A,9007199254740993.01,0,4\n");
            run.discover();
            JsonNode totals = run.success("analyzeDataset", run.analysis()).path("data").path("totals");
            assertEquals(new BigDecimal("0.3"), new BigDecimal(totals.path("before").path("net").asText()));
            assertEquals("9007199254740993.01", totals.path("delta").asText());
        }
    }

    @Test void zeroNegativeAndOneSidedGroupsDoNotInventPercentagesOrZeros() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,Z,0,0,1\n2026-08-01,N,1,3,1\n2026-08-02,B,2,0,1\n");
            write(run, "sales-current.csv", HEADER + "2026-09-01,Z,2,0,1\n2026-09-01,N,3,0,1\n2026-09-02,C,4,0,1\n");
            run.discover();
            JsonNode data = run.success("analyzeDataset", run.analysis()).path("data");
            assertTrue(group(data, "Z").get("percentChange").isNull());
            assertEquals("ZERO_BASE", group(data, "Z").get("percentChangeReason").asText());
            assertTrue(group(data, "N").get("percentChange").isNull());
            assertEquals("NON_POSITIVE_BASE", group(data, "N").get("percentChangeReason").asText());
            assertEquals("ONLY_BASELINE", group(data, "B").get("status").asText());
            assertTrue(group(data, "B").get("after").isNull());
            assertTrue(group(data, "B").get("delta").isNull());
            assertEquals("ONLY_COMPARISON", group(data, "C").get("status").asText());
            assertTrue(group(data, "C").get("before").isNull());
            assertEquals(2, data.get("ranking").size());
            assertTrue(data.get("warnings").toString().contains("ONE_SIDED_GROUPS"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "NaN", "Infinity", " ", "1,000", "0x10", "1e999", "1e-999"})
    void invalidOrUnboundedSelectedNumbersFailInsteadOfBecomingZero(String value) throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,A,\"" + value + "\",0,1\n");
            run.discover();
            assertEquals("INVALID_NUMBER", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.0", "", "9223372036854775808"})
    void countMustBeNonnegativeIntegralAndBounded(String count) throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,A,1,0," + count + "\n");
            run.discover();
            assertEquals("INVALID_NUMBER", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @Test void countOverflowIsAnExplicitError() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,A,1,0,9223372036854775807\n2026-08-02,A,1,0,1\n");
            run.discover();
            assertEquals("INVALID_NUMBER", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @Test void invalidDatesCannotBeSilentlyFilteredAndEmptyWindowIsNotZero() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            write(run, "sales-baseline.csv", HEADER + "2026-02-30,A,1,0,1\n");
            run.discover();
            assertEquals("INVALID_DATE", run.failure("analyzeDataset", run.analysis()));
        }
        try (SampleRun run = new SampleRun(root.resolve("empty-window"), true)) {
            run.discover();
            ObjectNode arguments = run.analysis();
            ((ObjectNode) arguments.get("baseline")).put("startDate", "2026-01-01").put("endDate", "2026-01-02");
            assertEquals("EMPTY_SELECTION", run.failure("analyzeDataset", arguments));
            ((ObjectNode) arguments.get("baseline")).put("startDate", "2026-03-01").put("endDate", "2026-01-02");
            assertEquals("INVALID_ARGUMENT", run.failure("analyzeDataset", arguments));
        }
    }

    @Test void numericValidationCoversAllSelectedRowsNotOnlyInspectionSamples() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,A,1,0,1\n".repeat(3) + "2026-08-02,A,bad,0,1\n");
            run.discover();
            assertEquals(3, run.success("inspectDataset", run.file("sales-baseline.csv")).path("data").path("samples").size());
            assertEquals("INVALID_NUMBER", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @Test void bomEscapedQuotesCommasAndEmbeddedNewlinesAreParsedByCommonsCsv() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            String quoted = "\"A,\"\"B\"\"\nC\"";
            write(run, "sales-baseline.csv", "\uFEFF" + HEADER + "2026-08-01," + quoted + ",0.10,0,1\n");
            write(run, "sales-current.csv", HEADER + "2026-09-01," + quoted + ",0.30,0,1\n");
            run.discover();
            JsonNode inspection = run.success("inspectDataset", run.file("sales-baseline.csv"));
            assertEquals("date", inspection.path("data").path("columns").get(0).asText());
            assertEquals("A,\"B\"\nC", inspection.path("data").path("samples").get(0).path("channel").path("value").asText());
            JsonNode data = run.success("analyzeDataset", run.analysis()).path("data");
            assertEquals("0.20", data.path("totals").path("delta").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"a,a\n1,2\n", "a,b\n\"unterminated,2", "a,b\n1,2,3\n", "a,\n1,2\n"})
    void malformedCsvAndDuplicateHeadersFail(String csv) throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            write(run, "bad.csv", csv);
            run.discover();
            assertEquals("INVALID_CSV", run.failure("inspectDataset", run.file("bad.csv")));
        }
    }

    @Test void rowColumnAndUnionGroupLimitsAreEnforced() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            write(run, "rows.csv", "a\n" + "1\n".repeat(5001));
            write(run, "columns.csv", String.join(",", java.util.stream.IntStream.range(0, 13).mapToObj(i -> "c" + i).toList())
                    + "\n" + String.join(",", java.util.Collections.nCopies(13, "1")) + "\n");
            StringBuilder before = new StringBuilder(HEADER);
            StringBuilder after = new StringBuilder(HEADER);
            for (int i = 0; i < 10; i++) {
                before.append("2026-08-01,G").append(i).append(",1,0,1\n");
                after.append("2026-09-01,G").append(i + 1).append(",1,0,1\n");
            }
            write(run, "sales-baseline.csv", before.toString());
            write(run, "sales-current.csv", after.toString());
            run.discover();
            assertEquals("ROW_LIMIT", run.failure("inspectDataset", run.file("rows.csv")));
            assertEquals("COLUMN_LIMIT", run.failure("inspectDataset", run.file("columns.csv")));
            assertEquals("GROUP_LIMIT", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @Test void filteredGroupsAreExplicitAndRankingTiesAreStable() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            write(run, "sales-baseline.csv", HEADER + "2026-08-01,B,2,0,1\n2026-08-01,A,1,0,1\n");
            write(run, "sales-current.csv", HEADER + "2026-09-01,B,1,0,1\n2026-09-01,A,2,0,1\n");
            run.discover();
            JsonNode all = run.success("analyzeDataset", run.analysis()).path("data");
            assertEquals("A", all.path("ranking").get(0).path("group").asText());
            ObjectNode filtered = run.analysis();
            filtered.putArray("groups").add("B");
            JsonNode result = run.success("analyzeDataset", filtered);
            assertEquals("FILTERED_GROUPS", result.path("data").path("totals").path("scope").asText());
            assertEquals(filtered.get("groups"), result.path("sources").get("groups"));
        }
    }

    @Test void largeExactResultsFailWithoutTruncatingTheAnalysis() throws Exception {
        try (SampleRun run = new SampleRun(root, false)) {
            StringBuilder before = new StringBuilder(HEADER);
            StringBuilder after = new StringBuilder(HEADER);
            String amount = "9".repeat(100) + "e100";
            String deduction = "8".repeat(99) + "e100";
            for (int i = 0; i < 10; i++) {
                String group = "G" + i + "x".repeat(125);
                before.append("2026-08-01,").append(group).append(',').append(amount).append(',').append(deduction).append(",1\n");
                after.append("2026-09-01,").append(group).append(',').append(amount).append(',').append(deduction).append(",1\n");
            }
            write(run, "sales-baseline.csv", before.toString());
            write(run, "sales-current.csv", after.toString());
            run.discover();
            assertEquals("RESULT_LIMIT", run.failure("analyzeDataset", run.analysis()));
        }
    }

    @Test void strictArgumentsRejectUnknownPropertiesTypesLengthsAndDuplicateJsonKeys() throws Exception {
        try (SampleRun run = new SampleRun(root, true)) {
            assertEquals("INVALID_ARGUMENT", run.failure("listWorkspaceFiles", WorkspaceJson.object().put("path", "../")));
            assertEquals("INVALID_ARGUMENT", assertInstanceOf(ToolOutcome.Failure.class, run.callRaw("listWorkspaceFiles", "{} {}" )).code());
            assertEquals("INVALID_ARGUMENT", assertInstanceOf(ToolOutcome.Failure.class,
                    run.callRaw("inspectDataset", "{\"fileId\":\"a\",\"fileId\":\"b\"}")).code());
            assertEquals("INVALID_ARGUMENT", run.failure("inspectDataset", WorkspaceJson.object().put("fileId", 3)));
            run.discover();
            ObjectNode args = run.analysis();
            ((ObjectNode) args.get("baseline")).put("sql", "select 1");
            assertEquals("INVALID_ARGUMENT", run.failure("analyzeDataset", args));
            ObjectNode groups = run.analysis();
            groups.putArray("groups").add("A").add("A");
            assertEquals("INVALID_ARGUMENT", run.failure("analyzeDataset", groups));
        }
    }

    static JsonNode group(JsonNode data, String name) {
        for (JsonNode group : data.get("groups")) if (group.get("group").asText().equals(name)) return group;
        throw new AssertionError("Missing group " + name);
    }

    private static void assertGroup(JsonNode data, String name, String before, String after, String delta, String percent,
                                    String beforeCount, String afterCount) {
        JsonNode group = group(data, name);
        assertEquals(before, group.path("before").path("net").asText());
        assertEquals(after, group.path("after").path("net").asText());
        assertEquals(delta, group.path("delta").asText());
        assertEquals(percent, group.path("percentChange").asText());
        assertEquals(beforeCount, group.path("before").path("count").asText());
        assertEquals(afterCount, group.path("after").path("count").asText());
    }

    private static void write(SampleRun run, String name, String text) throws Exception {
        Files.writeString(run.workspace.inputRoot().resolve(name), text);
    }
}
