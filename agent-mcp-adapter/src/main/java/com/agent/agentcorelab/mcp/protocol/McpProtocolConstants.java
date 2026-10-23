package com.agent.agentcorelab.mcp.protocol;

/**
 * Standard protocol constants for Model Context Protocol (MCP) JSON-RPC 2.0 specifications.
 */
public final class McpProtocolConstants {

    private McpProtocolConstants() {}

    public static final String JSONRPC_VERSION = "2.0";

    // Supported Model Context Protocol (MCP) versions
    public static final String PROTOCOL_VERSION_2024_11_05 = "2024-11-05";
    public static final String PROTOCOL_VERSION_2025_06_18 = "2025-06-18";
    public static final String PROTOCOL_VERSION_2025_11_25 = "2025-11-25";

    public static final String LATEST_PROTOCOL_VERSION = PROTOCOL_VERSION_2025_06_18;
    public static final String PROTOCOL_VERSION = LATEST_PROTOCOL_VERSION;

    // Core Lifecycle & Utility Methods
    public static final String METHOD_INITIALIZE = "initialize";
    public static final String METHOD_NOTIFICATIONS_INITIALIZED = "notifications/initialized";
    public static final String METHOD_PING = "ping";

    // Tools API
    public static final String METHOD_TOOLS_LIST = "tools/list";
    public static final String METHOD_TOOLS_CALL = "tools/call";

    // Resources & Prompts API (Optional extensions)
    public static final String METHOD_RESOURCES_LIST = "resources/list";
    public static final String METHOD_PROMPTS_LIST = "prompts/list";

    // Standard JSON-RPC 2.0 Error Codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final int ERROR_INVALID_PARAMS = INVALID_PARAMS;
    public static final int ERROR_METHOD_NOT_FOUND = METHOD_NOT_FOUND;
    public static final int ERROR_INTERNAL = INTERNAL_ERROR;
}
