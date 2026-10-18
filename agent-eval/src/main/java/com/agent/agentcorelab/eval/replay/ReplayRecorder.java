package com.agent.agentcorelab.eval.replay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe recorder and lookup repository for deterministic replay records.
 */
public class ReplayRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, ReplayRecord> store = new ConcurrentHashMap<>();

    public ReplayRecorder() {
    }

    public void put(ReplayRecord record) {
        if (record != null && record.fingerprint() != null) {
            store.put(record.fingerprint(), record);
        }
    }

    public Optional<ReplayRecord> lookup(String fingerprint) {
        if (fingerprint == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(fingerprint));
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }

    public Map<String, ReplayRecord> snapshot() {
        return Map.copyOf(store);
    }

    public void save(File file) throws IOException {
        MAPPER.writeValue(file, store);
    }

    public void save(OutputStream out) throws IOException {
        MAPPER.writeValue(out, store);
    }

    public void load(File file) throws IOException {
        if (file.exists()) {
            Map<String, ReplayRecord> loaded = MAPPER.readValue(file, new TypeReference<Map<String, ReplayRecord>>() {});
            store.putAll(loaded);
        }
    }

    public void load(InputStream in) throws IOException {
        Map<String, ReplayRecord> loaded = MAPPER.readValue(in, new TypeReference<Map<String, ReplayRecord>>() {});
        store.putAll(loaded);
    }

    public static ReplayRecorder loadFromFile(java.nio.file.Path path) throws IOException {
        ReplayRecorder recorder = new ReplayRecorder();
        recorder.load(path.toFile());
        return recorder;
    }
}
