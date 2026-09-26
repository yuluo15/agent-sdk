package com.agent.agentcorelab.sample.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Objects;

/** Trusted task constraints, not expected numeric answers and not parsed from model text. */
public record ComparisonTarget(Side baseline, Side comparison) {
    public ComparisonTarget {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(comparison, "comparison");
    }

    public record Side(String fileName, LocalDate startDate, LocalDate endDate, String dateColumn,
                       String groupColumn, String valueColumn, String deductionColumn, String countColumn) {
        public Side {
            for (String field : new String[]{fileName, dateColumn, groupColumn, valueColumn, deductionColumn}) {
                if (field == null || field.isBlank()) throw new IllegalArgumentException("Explicit source and metric mappings are required");
            }
            Objects.requireNonNull(startDate, "startDate");
            Objects.requireNonNull(endDate, "endDate");
            if (startDate.isAfter(endDate)) throw new IllegalArgumentException("Target dates are reversed");
        }

        boolean matches(JsonNode source) {
            return fileName.equals(source.path("name").asText())
                    && startDate.toString().equals(source.path("startDate").asText())
                    && endDate.toString().equals(source.path("endDate").asText())
                    && dateColumn.equals(source.path("dateColumn").asText())
                    && groupColumn.equals(source.path("groupColumn").asText())
                    && valueColumn.equals(source.path("valueColumn").asText())
                    && deductionColumn.equals(source.path("deductionColumn").asText())
                    && (countColumn == null ? !source.has("countColumn") : countColumn.equals(source.path("countColumn").asText()));
        }

        String describe() {
            return fileName + " [" + startDate + ".." + endDate + "]：日期=" + dateColumn + "，全组=" + groupColumn
                    + "，净额=" + valueColumn + "-" + deductionColumn
                    + (countColumn == null ? "" : "，计数=" + countColumn);
        }
    }

    public static ComparisonTarget exercise() {
        return new ComparisonTarget(
                new Side("sales-baseline.csv", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2),
                        "date", "channel", "gross_sales", "refund_amount", "order_count"),
                new Side("sales-current.csv", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2),
                        "date", "channel", "gross_sales", "refund_amount", "order_count"));
    }

    public String description() { return "基准：" + baseline.describe() + "；对比：" + comparison.describe(); }
}
