package com.agent.agentcorelab.trace;

import com.agent.agentcorelab.tool.ToolJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonlRunEventSinkTests {
    @TempDir
    Path directory;

    @Test
    void writesOrderedParseableEventsWithoutOverwritingEarlierOnes() throws Exception {
        var sink = new JsonlRunEventSink(directory);
        UUID run = UUID.randomUUID();
        sink.publish(new RunEvent(1, run, Instant.now(), RunEvent.Type.RUN_STARTED, "{\"usage\":\"unknown\"}"));
        sink.publish(new RunEvent(2, run, Instant.now(), RunEvent.Type.RUN_FINISHED, "{\"reason\":\"CANCELLED\"}"));
        var lines = Files.readAllLines(directory.resolve(run + ".jsonl"));
        assertEquals(2, lines.size());
        assertEquals(1, ToolJson.parse(lines.getFirst()).path("sequence").asInt());
        assertEquals("unknown", ToolJson.parse(lines.getFirst()).path("details").path("usage").asText());
        assertEquals("CANCELLED", ToolJson.parse(lines.getLast()).path("details").path("reason").asText());
    }

    @Test
    void refusesNonFileDestination() throws Exception {
        UUID run = UUID.randomUUID();
        Files.createDirectory(directory.resolve(run + ".jsonl"));
        var sink = new JsonlRunEventSink(directory);
        assertThrows(UncheckedIOException.class, () -> sink.publish(
                new RunEvent(1, run, Instant.now(), RunEvent.Type.RUN_STARTED, "{}")));
    }
}
