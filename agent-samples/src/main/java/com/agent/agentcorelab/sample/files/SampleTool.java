package com.agent.agentcorelab.sample.files;

import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Stateless sample adapter. Executor also validates the published JSON schema. */
public abstract class SampleTool implements Tool {
    private final ToolDefinition definition;
    private final JsonNode schema;

    protected SampleTool(String name, String description, ObjectNode schema) {
        this.schema = schema.deepCopy();
        try {
            definition = new ToolDefinition(name, description, WorkspaceJson.encode(schema));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public final ToolDefinition definition() { return definition; }

    @Override public final ToolOutcome execute(String argumentsJson, ToolContext context) {
        context.checkActive();
        try {
            if (context.resources() == null || !context.runId().equals(context.resources().runId())) {
                throw new WorkspaceException("RUN_MISMATCH", "Tool resources must belong to the current run", true);
            }
            JsonNode arguments;
            try {
                if (argumentsJson == null || WorkspaceJson.byteLength(argumentsJson) > 128 * 1024) {
                    throw new IllegalArgumentException("Tool arguments exceed the supported size");
                }
                arguments = WorkspaceJson.parse(argumentsJson);
                validate(arguments, schema);
            } catch (IOException e) {
                throw new IllegalArgumentException("Provide one valid JSON object with unique keys");
            }
            return new ToolOutcome.Success(executeValidated(arguments, context));
        } catch (WorkspaceException e) {
            return new ToolOutcome.Failure(e.code(), e.getMessage(), e.terminal());
        } catch (IllegalArgumentException e) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Arguments do not satisfy the tool schema or supported range", false);
        } catch (IOException e) {
            return new ToolOutcome.Failure("WORKSPACE_IO_ERROR", "Workspace I/O failed; inspect the local workspace", true);
        }
    }

    protected abstract String executeValidated(JsonNode arguments, ToolContext context) throws IOException;

    public static ObjectNode objectSchema(ObjectNode properties, String... required) {
        ObjectNode result = WorkspaceJson.object().put("type", "object").put("additionalProperties", false);
        result.set("properties", properties);
        ArrayNode names = result.putArray("required");
        for (String name : required) names.add(name);
        return result;
    }

    public static ObjectNode textSchema(int min, int max) {
        return WorkspaceJson.object().put("type", "string").put("minLength", min).put("maxLength", max);
    }

    public static ObjectNode integerSchema(int min, int max, int defaultValue) {
        return WorkspaceJson.object().put("type", "integer").put("minimum", min).put("maximum", max).put("default", defaultValue);
    }

    public static ObjectNode arraySchema(JsonNode items, int min, int max, boolean unique) {
        ObjectNode schema = WorkspaceJson.object().put("type", "array").put("minItems", min).put("maxItems", max)
                .put("uniqueItems", unique);
        schema.set("items", items);
        return schema;
    }

    public static ObjectNode fileIdSchema() {
        ObjectNode fields = WorkspaceJson.object();
        fields.set("fileId", textSchema(1, 64));
        return objectSchema(fields, "fileId");
    }

    private static void validate(JsonNode value, JsonNode schema) {
        switch (schema.path("type").asText()) {
            case "object" -> {
                require(value.isObject());
                JsonNode fields = schema.path("properties");
                schema.path("required").forEach(name -> require(value.has(name.asText())));
                Iterator<String> names = value.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    require(fields.has(name));
                    validate(value.get(name), fields.get(name));
                }
            }
            case "string" -> {
                require(value.isTextual());
                String text = value.textValue();
                int length = text.codePointCount(0, text.length());
                require(length >= schema.path("minLength").asInt() && length <= schema.path("maxLength").asInt());
                if (schema.has("pattern")) require(text.matches(schema.get("pattern").asText()));
            }
            case "integer" -> require(value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= schema.path("minimum").asInt()
                    && value.intValue() <= schema.path("maximum").asInt());
            case "array" -> {
                require(value.isArray() && value.size() >= schema.path("minItems").asInt()
                        && value.size() <= schema.path("maxItems").asInt());
                Set<JsonNode> seen = new HashSet<>();
                for (JsonNode item : value) {
                    validate(item, schema.get("items"));
                    if (schema.path("uniqueItems").asBoolean()) require(seen.add(item));
                }
            }
            default -> throw new IllegalStateException("Unsupported sample schema type");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid tool argument");
    }
}
