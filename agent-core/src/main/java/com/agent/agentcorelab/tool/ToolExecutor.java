package com.agent.agentcorelab.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class ToolExecutor {
    private final ToolCatalog catalog;
    private final Map<String, JsonSchema> schemas;
    private final int maxResultBytes;

    public ToolExecutor(ToolCatalog catalog, int maxResultBytes) {
        this.catalog = catalog;
        this.maxResultBytes = maxResultBytes;
        Map<String, JsonSchema> compiled = new HashMap<>();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (ToolDefinition definition : catalog.definitions()) {
            JsonNode schema = ToolJson.parse(definition.inputSchema());
            if (!schema.isObject() || !"object".equals(schema.path("type").asText())) {
                throw new IllegalArgumentException("Tool schema must describe an object: " + definition.name());
            }
            rejectReferences(schema);
            compiled.put(definition.name(), factory.getSchema(schema));
        }
        schemas = Map.copyOf(compiled);
    }

    public ToolOutcome.Failure validate(ToolCall call) {
        JsonSchema schema = schemas.get(call.name());
        if (schema == null) {
            return new ToolOutcome.Failure("UNKNOWN_TOOL", "Tool is not allowed for this Agent", false);
        }
        try {
            JsonNode arguments = ToolJson.parse(call.argumentsJson());
            if (!arguments.isObject() || !schema.validate(arguments).isEmpty()) {
                return new ToolOutcome.Failure("INVALID_ARGUMENT", "Arguments must match the tool JSON schema", false);
            }
            return null;
        } catch (IllegalArgumentException ex) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Arguments must be one bounded valid JSON object", false);
        }
    }

    public ToolOutcome execute(ToolCall call, ToolContext context) {
        ToolOutcome.Failure invalid = validate(call);
        if (invalid != null) {
            return invalid;
        }
        context.checkActive();
        try {
            ToolOutcome outcome = catalog.require(call.name()).execute(call.argumentsJson(), context);
            if (outcome instanceof ToolOutcome.Success success) {
                if (success.outputJson() == null) {
                    return invalidOutput();
                }
                try {
                    if (!ToolJson.parse(success.outputJson()).isObject()) {
                        return invalidOutput();
                    }
                } catch (IllegalArgumentException ex) {
                    return invalidOutput();
                }
                if (success.outputJson().getBytes(StandardCharsets.UTF_8).length > maxResultBytes) {
                    if (context != null && context.resources() != null) {
                        try {
                            var item = context.resources().saveWorkItem(call.name(), call.callId(), success.outputJson());
                            String offloadedJson = ToolJson.write(Map.of(
                                    "ok", true,
                                    "_offloaded", true,
                                    "itemId", item.itemId(),
                                    "tool", item.tool(),
                                    "bytes", item.bytes(),
                                    "preview", item.preview(),
                                    "note", "Tool output exceeds inline limit (" + maxResultBytes
                                            + " bytes) and was offloaded to workspace. Use readWorkItem tool with itemId to read slices."
                            ));
                            return new ToolOutcome.Success(offloadedJson);
                        } catch (IOException ex) {
                            return new ToolOutcome.Failure("STORAGE_FAILED", "Failed to offload tool result to workspace", true);
                        }
                    }
                    return new ToolOutcome.Failure("TOOL_RESULT_LIMIT", "Tool result exceeds its configured limit", true);
                }
            } else if (outcome instanceof ToolOutcome.Failure failure) {
                String json = ToolJson.write(Map.of("ok", false, "error",
                        Map.of("code", failure.code(), "message", failure.message())));
                if (json.getBytes(StandardCharsets.UTF_8).length > maxResultBytes) {
                    return new ToolOutcome.Failure("TOOL_RESULT_LIMIT", "Tool error exceeds its configured limit", true);
                }
            } else {
                return new ToolOutcome.Failure("TOOL_PROTOCOL_ERROR", "Tool returned no result", true);
            }
            return outcome;
        } catch (CancellationException | ActiveTimeExceededException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return new ToolOutcome.Failure("TOOL_EXECUTION_FAILED", "Tool could not complete; execution will not be retried", true);
        }
    }

    private static ToolOutcome.Failure invalidOutput() {
        return new ToolOutcome.Failure("TOOL_PROTOCOL_ERROR",
                "Tool output must be a valid JSON object; wrap arrays or text, e.g. {\"data\":[]} or {\"data\":\"text\"}", true);
    }

    public static String fingerprint(ToolCall call) {
        try {
            return call.name() + "\n" + ToolJson.canonical(ToolJson.parse(call.argumentsJson()));
        } catch (IllegalArgumentException ex) {
            return call.name() + "\n" + call.argumentsJson();
        }
    }

    private static void rejectReferences(JsonNode node) {
        if (node.isObject() && (node.has("$ref") || node.has("$dynamicRef"))) {
            throw new IllegalArgumentException("Tool schemas must be self-contained");
        }
        node.forEach(ToolExecutor::rejectReferences);
    }
}
