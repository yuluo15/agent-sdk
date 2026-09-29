package com.agent.agentcorelab.mcp.client;

import com.agent.agentcorelab.mcp.protocol.McpInitializeResult;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * High-level client SPI for interacting with an individual MCP server.
 */
public interface McpClient extends AutoCloseable {

    /**
     * Unique logical name identifying this MCP server connection.
     */
    String serverName();

    /**
     * Synchronously connects and performs protocol negotiation with the MCP server.
     */
    McpInitializeResult initialize();

    /**
     * Asynchronously connects and performs protocol negotiation with the MCP server.
     */
    CompletableFuture<McpInitializeResult> initializeAsync();

    /**
     * Synchronously lists all available tools exposed by the MCP server.
     */
    List<McpTool> listTools();

    /**
     * Asynchronously lists all available tools exposed by the MCP server.
     */
    CompletableFuture<List<McpTool>> listToolsAsync();

    /**
     * Synchronously invokes a specific tool on the MCP server.
     *
     * @param toolName  name of the tool to execute
     * @param arguments JSON arguments matching the tool's inputSchema
     * @return tool execution outcome
     */
    McpToolResult callTool(String toolName, JsonNode arguments);

    /**
     * Asynchronously invokes a specific tool on the MCP server.
     */
    CompletableFuture<McpToolResult> callToolAsync(String toolName, JsonNode arguments);

    /**
     * Pings the server and measures round-trip latency.
     */
    Duration ping();

    /**
     * Checks if the client has successfully initialized and is ready for tool calls.
     */
    boolean isConnected();

    /**
     * Closes the client connection and releases transport resources.
     */
    @Override
    void close();
}
