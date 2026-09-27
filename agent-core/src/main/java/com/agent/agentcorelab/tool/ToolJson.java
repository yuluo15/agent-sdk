package com.agent.agentcorelab.tool;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.TreeMap;

public final class ToolJson {
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(65536).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private ToolJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode parse(String value) {
        if (value == null || value.length() > 65536) {
            throw new IllegalArgumentException("JSON is absent or too large");
        }
        try {
            JsonNode result = MAPPER.readTree(value);
            if (result == null) {
                throw new IllegalArgumentException("JSON is empty");
            }
            return result;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Cannot encode JSON", ex);
        }
    }

    public static String canonical(JsonNode value) {
        return ordered(value).toString();
    }

    private static JsonNode ordered(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(field -> fields.put(field.getKey(), ordered(field.getValue())));
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            value.forEach(item -> result.add(ordered(item)));
            return result;
        }
        return value;
    }
}
