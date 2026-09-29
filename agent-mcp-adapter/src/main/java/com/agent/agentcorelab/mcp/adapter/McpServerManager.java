package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages multiple {@link McpServerBridge} instances, aggregating their tools
 * and centralizing their lifecycle shutdown.
 */
public final class McpServerManager implements AutoCloseable {

    private final Map<String, McpServerBridge> bridges = new LinkedHashMap<>();

    public McpServerManager() {
    }

    public McpServerManager(Collection<McpServerBridge> initialBridges) {
        if (initialBridges != null) {
            for (McpServerBridge bridge : initialBridges) {
                registerBridge(bridge);
            }
        }
    }

    public synchronized void registerBridge(McpServerBridge bridge) {
        Objects.requireNonNull(bridge, "bridge cannot be null");
        bridges.put(bridge.serverName(), bridge);
    }

    public synchronized Optional<McpServerBridge> getBridge(String serverName) {
        return Optional.ofNullable(bridges.get(serverName));
    }

    public synchronized List<McpServerBridge> listBridges() {
        return List.copyOf(bridges.values());
    }

    /**
     * Collects all bridged tools across all registered MCP servers.
     */
    public synchronized List<Tool> allTools() {
        List<Tool> all = new ArrayList<>();
        for (McpServerBridge bridge : bridges.values()) {
            all.addAll(bridge.getTools());
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Finds a tool by name across all registered MCP bridges.
     */
    public synchronized Optional<Tool> findTool(String toolName) {
        for (McpServerBridge bridge : bridges.values()) {
            Tool tool = bridge.findTool(toolName);
            if (tool != null) {
                return Optional.of(tool);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void close() {
        for (McpServerBridge bridge : bridges.values()) {
            try {
                bridge.close();
            } catch (Exception ignored) {
            }
        }
        bridges.clear();
    }
}
