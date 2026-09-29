package com.agent.agentcorelab.mcp.protocol;

/**
 * Represents a content block returned by an MCP tool or resource.
 */
public record McpContent(
        String type,
        String text,
        String data,
        String mimeType,
        String uri
) {
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_RESOURCE = "resource";

    public McpContent {
        type = type != null ? type : TYPE_TEXT;
    }

    public McpContent(String type, String text, String data, String mimeType) {
        this(type, text, data, mimeType, null);
    }

    public static McpContent text(String text) {
        return new McpContent(TYPE_TEXT, text != null ? text : "", null, null, null);
    }

    public static McpContent image(String base64Data, String mimeType) {
        return new McpContent(TYPE_IMAGE, null, base64Data, mimeType, null);
    }

    public static McpContent resource(String uri, String text) {
        return new McpContent(TYPE_RESOURCE, text, null, null, uri);
    }
}
