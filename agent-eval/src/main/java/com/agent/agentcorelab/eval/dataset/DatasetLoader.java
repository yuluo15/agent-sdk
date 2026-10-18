package com.agent.agentcorelab.eval.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads and validates evaluation dataset files from JSON resources or input streams.
 */
public final class DatasetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private DatasetLoader() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Loads a dataset from a classpath resource path.
     *
     * @param resourcePath classpath path (e.g. "/eval/datasets/phase0-v1.json" or "eval/datasets/phase0-v1.json")
     * @return list of parsed EvalCase objects
     */
    public static List<EvalCase> loadFromClasspath(String resourcePath) {
        String path = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream in = DatasetLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Dataset resource not found on classpath: " + resourcePath);
            }
            return loadFromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dataset from classpath: " + resourcePath, e);
        }
    }

    /**
     * Loads a dataset from an InputStream.
     */
    public static List<EvalCase> loadFromStream(InputStream in) throws IOException {
        return MAPPER.readValue(in, new TypeReference<List<EvalCase>>() {});
    }

    /**
     * Loads a dataset from a raw JSON string.
     */
    public static List<EvalCase> loadFromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON content is null or blank");
        }
        try {
            return MAPPER.readValue(json.getBytes(StandardCharsets.UTF_8), new TypeReference<List<EvalCase>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid dataset JSON format", e);
        }
    }

    /**
     * Loads a dataset from a filesystem path.
     */
    public static List<EvalCase> loadFromFile(java.nio.file.Path path) {
        try (InputStream in = java.nio.file.Files.newInputStream(path)) {
            return loadFromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read dataset from file: " + path, e);
        }
    }
}
