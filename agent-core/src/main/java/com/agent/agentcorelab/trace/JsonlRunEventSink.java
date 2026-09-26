package com.agent.agentcorelab.trace;

import com.agent.agentcorelab.tool.ToolJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class JsonlRunEventSink implements RunEventSink {
    private final Path root;

    public JsonlRunEventSink(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public synchronized void publish(RunEvent event) {
        Path file = root.resolve(event.runId() + ".jsonl");
        try {
            if (!root.toRealPath().equals(root) || Files.isSymbolicLink(root)
                    || Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))) {
                throw new IOException("Trace path is not a regular workspace path");
            }
            String json = ToolJson.write(Map.of("sequence", event.sequence(), "runId", event.runId().toString(),
                    "occurredAt", event.occurredAt().toString(), "type", event.type().name(),
                    "details", ToolJson.parse(event.summary())));
            Files.writeString(file, json + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not record run event", ex);
        }
    }
}
