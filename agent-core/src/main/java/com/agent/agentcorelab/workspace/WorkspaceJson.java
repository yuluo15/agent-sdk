package com.agent.agentcorelab.workspace;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

/** Jackson 2 encoding shared by observations and the verifiable report appendix. */
public final class WorkspaceJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private WorkspaceJson() { }

    public static ObjectNode object() { return MAPPER.createObjectNode(); }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }

    public static JsonNode parse(String json) throws IOException {
        try (JsonParser parser = MAPPER.createParser(json)) {
            JsonNode node = MAPPER.readTree(parser);
            if (node == null || parser.nextToken() != null) {
                throw new IOException("Expected one JSON value");
            }
            return node;
        }
    }

    public static String encode(JsonNode node) throws IOException {
        return MAPPER.writeValueAsString(node);
    }

    public static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = object();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = array();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node.deepCopy();
    }

    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
