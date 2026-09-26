package com.agent.agentcorelab.sample.analysis;

import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceSession;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

record CsvDataset(WorkspaceSession.FileEntry file, List<String> columns, List<CSVRecord> rows) {
    static CsvDataset load(WorkspaceSession session, String fileId, Runnable checkActive) throws IOException {
        WorkspaceSession.Snapshot snapshot = session.read(fileId);
        if (!snapshot.file().type().equals("csv")) throw error("INVALID_FILE_TYPE", "Select a CSV file");
        CSVFormat format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setAllowMissingColumnNames(false).get();
        try (CSVParser parser = format.parse(new StringReader(snapshot.utf8()))) {
            List<String> headers = parser.getHeaderNames();
            if (headers.isEmpty()) throw error("EMPTY_DATASET", "CSV must have a header and data rows");
            if (headers.size() > 12) throw error("COLUMN_LIMIT", "CSV supports at most 12 columns");
            if (headers.stream().anyMatch(name -> name.isBlank() || name.length() > 128)) {
                throw error("INVALID_CSV", "Headers must be nonempty and at most 128 characters");
            }
            List<CSVRecord> rows = new ArrayList<>();
            for (CSVRecord row : parser) {
                checkActive.run();
                if (!row.isConsistent()) throw error("INVALID_CSV", "Every row must have the same number of fields as the header");
                if (rows.size() == 5000) throw error("ROW_LIMIT", "CSV supports at most 5000 data rows");
                rows.add(row);
            }
            if (rows.isEmpty()) throw error("EMPTY_DATASET", "CSV has no data rows");
            return new CsvDataset(snapshot.file(), List.copyOf(headers), List.copyOf(rows));
        } catch (UncheckedIOException | IllegalArgumentException e) {
            throw error("INVALID_CSV", "CSV quoting, headers or records are invalid; duplicate headers are not allowed");
        } catch (WorkspaceException e) {
            throw e;
        } catch (IOException e) {
            throw error("INVALID_CSV", "CSV quoting or records are invalid");
        }
    }

    static BigDecimal number(String text) throws WorkspaceException {
        if (text.length() > 128 || !text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw error("INVALID_NUMBER", "Selected amount must be a nonempty finite decimal number");
        }
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.precision() > 100 || value.scale() < -100 || value.scale() > 100) {
                throw error("INVALID_NUMBER", "Decimal precision and scale must be within supported bounds");
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("INVALID_NUMBER", "Selected amount is not a supported decimal number");
        }
    }

    static long count(String text) throws WorkspaceException {
        if (!text.matches("[+]?[0-9]{1,19}")) throw error("INVALID_NUMBER", "Count must be a nonnegative 64-bit integer");
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw error("INVALID_NUMBER", "Count exceeds signed 64-bit range"); }
    }

    static LocalDate date(String text) throws WorkspaceException {
        try {
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeParseException("ISO date required", text, 0);
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw error("INVALID_DATE", "Dates must be valid ISO yyyy-MM-dd dates");
        }
    }

    static WorkspaceException error(String code, String message) { return new WorkspaceException(code, message, false); }
}
