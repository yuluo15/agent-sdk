package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.sample.files.SampleTool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded grouped comparison; no expressions, inferred mappings or model arithmetic. */
public final class AnalyzeDatasetTool extends SampleTool {
    public AnalyzeDatasetTool() {
        super("analyzeDataset", "Compare two explicitly mapped CSV date windows using exact decimal sums. Net = value - deduction. Returns all groups, totals and absolute-delta ranking; no causal inference.", schema());
    }

    private static ObjectNode schema() {
        ObjectNode fields = WorkspaceJson.object();
        fields.set("baseline", sideSchema());
        fields.set("comparison", sideSchema());
        fields.set("groups", arraySchema(textSchema(1, 128), 1, 10, true));
        return objectSchema(fields, "baseline", "comparison");
    }

    private static ObjectNode sideSchema() {
        ObjectNode fields = WorkspaceJson.object();
        fields.set("fileId", textSchema(1, 64));
        for (String name : List.of("dateColumn", "groupColumn", "valueColumn", "deductionColumn", "countColumn")) {
            fields.set(name, textSchema(1, 128));
        }
        for (String name : List.of("startDate", "endDate")) {
            fields.set(name, textSchema(10, 10).put("format", "date").put("pattern", "[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        }
        return objectSchema(fields, "fileId", "dateColumn", "startDate", "endDate", "groupColumn", "valueColumn");
    }

    @Override protected String executeValidated(JsonNode arguments, ToolContext context) throws IOException {
        Set<String> selected = null;
        if (arguments.has("groups")) {
            selected = new HashSet<>();
            for (JsonNode group : arguments.get("groups")) {
                if (group.asText().isBlank()) throw CsvDataset.error("INVALID_ARGUMENT", "Group filters must be nonblank");
                selected.add(group.asText());
            }
        }
        Side baseline = aggregate(arguments.get("baseline"), selected, context);
        Side comparison = aggregate(arguments.get("comparison"), selected, context);
        TreeSet<String> names = new TreeSet<>(baseline.groups().keySet());
        names.addAll(comparison.groups().keySet());
        if (names.size() > 10) throw CsvDataset.error("GROUP_LIMIT", "Comparison supports at most 10 groups across both sides");
        ObjectNode sources = arguments.deepCopy();
        enrich((ObjectNode) sources.get("baseline"), baseline);
        enrich((ObjectNode) sources.get("comparison"), comparison);
        ObjectNode data = WorkspaceJson.object();
        ObjectNode coverage = data.putObject("coverage");
        coverage.set("baseline", baseline.coverage());
        coverage.set("comparison", comparison.coverage());
        ArrayNode groups = data.putArray("groups");
        List<Rank> ranking = new ArrayList<>();
        ArrayNode warnings = data.putArray("warnings");
        warnings.add("Observed dates and row counts do not prove interval completeness; consult references or user confirmation.");
        warnings.add("Comparable units and field semantics require reference or user confirmation; no external causal data is available.");
        boolean oneSided = false;
        for (String name : names) {
            context.checkActive();
            Totals before = baseline.groups().get(name);
            Totals after = comparison.groups().get(name);
            ObjectNode group = groups.addObject().put("group", name);
            group.set("before", before == null ? null : before.json());
            group.set("after", after == null ? null : after.json());
            if (before == null || after == null) {
                group.put("status", before == null ? "ONLY_COMPARISON" : "ONLY_BASELINE");
                group.putNull("delta").putNull("percentChange");
                oneSided = true;
            } else {
                group.put("status", "COMPARABLE");
                change(group, before.net(), after.net());
                ranking.add(new Rank(name, after.net().subtract(before.net())));
            }
        }
        if (oneSided) warnings.add("ONE_SIDED_GROUPS: common-group deltas need not sum to the overall delta; absent sides are not zero.");
        ObjectNode totals = data.putObject("totals").put("scope", selected == null ? "ALL_GROUPS" : "FILTERED_GROUPS");
        totals.set("before", baseline.total().json());
        totals.set("after", comparison.total().json());
        change(totals, baseline.total().net(), comparison.total().net());
        ArrayNode ranks = data.putArray("ranking");
        ranking.sort(Comparator.comparing((Rank rank) -> rank.delta().abs()).reversed().thenComparing(Rank::group));
        for (Rank rank : ranking) {
            ranks.addObject().put("group", rank.group()).put("delta", decimal(rank.delta()))
                    .put("absoluteDelta", decimal(rank.delta().abs()));
        }
        context.resources().verifyInputs(context::checkActive);
        context.checkActive();
        return context.resources().observe(definition().name(), sources, data,
                List.of(baseline.dataset().file().fileId(), comparison.dataset().file().fileId()));
    }

    private static void enrich(ObjectNode source, Side side) {
        source.put("name", side.dataset().file().name()).put("sha256", side.dataset().file().sha256());
    }

    private Side aggregate(JsonNode mapping, Set<String> selected, ToolContext context) throws IOException {
        LocalDate start = CsvDataset.date(mapping.get("startDate").asText());
        LocalDate end = CsvDataset.date(mapping.get("endDate").asText());
        if (start.isAfter(end)) throw CsvDataset.error("INVALID_ARGUMENT", "startDate must not exceed endDate");
        CsvDataset dataset = CsvDataset.load(context.resources(), mapping.get("fileId").asText(), context::checkActive);
        Set<String> mapped = new HashSet<>();
        for (String field : List.of("dateColumn", "groupColumn", "valueColumn", "deductionColumn", "countColumn")) {
            if (!mapping.has(field)) continue;
            String column = mapping.get(field).asText();
            if (!dataset.columns().contains(column)) throw CsvDataset.error("UNKNOWN_COLUMN", "A mapped column does not exist; inspect the CSV headers");
            if (!mapped.add(column)) throw CsvDataset.error("INVALID_ARGUMENT", "Date, group, amount, deduction and count must use distinct columns");
        }
        Map<String, Totals> groups = new TreeMap<>();
        boolean counted = mapping.has("countColumn");
        Totals total = new Totals(counted);
        int rows = 0;
        LocalDate first = null;
        LocalDate last = null;
        for (CSVRecord row : dataset.rows()) {
            context.checkActive();
            LocalDate date = CsvDataset.date(row.get(mapping.get("dateColumn").asText()));
            if (date.isBefore(start) || date.isAfter(end)) continue;
            String name = row.get(mapping.get("groupColumn").asText());
            if (name.isBlank() || name.length() > 128) throw CsvDataset.error("INVALID_GROUP", "Selected group names must be nonblank and at most 128 characters");
            if (selected != null && !selected.contains(name)) continue;
            BigDecimal amount = CsvDataset.number(row.get(mapping.get("valueColumn").asText()));
            BigDecimal deduction = mapping.has("deductionColumn") ? CsvDataset.number(row.get(mapping.get("deductionColumn").asText())) : BigDecimal.ZERO;
            long count = counted ? CsvDataset.count(row.get(mapping.get("countColumn").asText())) : 0;
            Totals group = groups.computeIfAbsent(name, ignored -> new Totals(counted));
            if (groups.size() > 10) throw CsvDataset.error("GROUP_LIMIT", "Selected data contains more than 10 groups");
            group.add(amount, deduction, count);
            total.add(amount, deduction, count);
            rows++;
            if (first == null || date.isBefore(first)) first = date;
            if (last == null || date.isAfter(last)) last = date;
        }
        if (rows == 0) throw CsvDataset.error("EMPTY_SELECTION", "One date/group selection has no rows; missing data is not zero");
        ObjectNode coverage = WorkspaceJson.object().put("matchedRows", rows).put("earliestDate", first.toString())
                .put("latestDate", last.toString()).put("provesCompleteness", false);
        return new Side(dataset, groups, total, coverage);
    }

    private static void change(ObjectNode node, BigDecimal before, BigDecimal after) {
        BigDecimal delta = after.subtract(before);
        node.put("delta", decimal(delta));
        if (before.signum() > 0) {
            node.put("percentChange", delta.multiply(BigDecimal.valueOf(100)).divide(before, 2, RoundingMode.HALF_UP).toPlainString());
        } else {
            node.putNull("percentChange").put("percentChangeReason", before.signum() == 0 ? "ZERO_BASE" : "NON_POSITIVE_BASE");
        }
    }

    private static String decimal(BigDecimal value) { return value.toPlainString(); }
    private record Rank(String group, BigDecimal delta) { }
    private record Side(CsvDataset dataset, Map<String, Totals> groups, Totals total, ObjectNode coverage) { }

    private static final class Totals {
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal deduction = BigDecimal.ZERO;
        private long count;
        private final boolean counted;
        private Totals(boolean counted) { this.counted = counted; }
        private void add(BigDecimal value, BigDecimal subtract, long orders) throws IOException {
            amount = amount.add(value);
            deduction = deduction.add(subtract);
            try { count = Math.addExact(count, orders); }
            catch (ArithmeticException e) { throw CsvDataset.error("INVALID_NUMBER", "Summed count exceeds signed 64-bit range"); }
        }
        private BigDecimal net() { return amount.subtract(deduction); }
        private ObjectNode json() {
            ObjectNode node = WorkspaceJson.object().put("amount", decimal(amount)).put("deduction", decimal(deduction)).put("net", decimal(net()));
            if (counted) node.put("count", Long.toString(count)); else node.putNull("count");
            return node;
        }
    }
}
