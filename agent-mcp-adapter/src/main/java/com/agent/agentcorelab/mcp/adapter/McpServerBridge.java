package com.agent.agentcorelab.mcp.adapter;

import com.agent.agentcorelab.mcp.client.McpClient;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Manages the lifecycle of an {@link McpClient} and bridges its remote tools
 * into a collection of SDK {@link Tool} instances.
 */
public final class McpServerBridge implements AutoCloseable {

    private final McpClient client;
    private final String toolPrefix;
    private final Predicate<String> toolFilter;
    private final Function<String, RiskLevel> riskResolver;
    private final ObjectMapper mapper;

    private final Map<String, Tool> bridgedTools = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    private McpServerBridge(Builder builder) {
        this.client = Objects.requireNonNull(builder.client, "client cannot be null");
        this.toolPrefix = builder.toolPrefix;
        this.toolFilter = builder.toolFilter != null ? builder.toolFilter : name -> true;
        this.riskResolver = builder.riskResolver != null ? builder.riskResolver : name -> RiskLevel.READ_ONLY;
        this.mapper = builder.mapper;
    }

    public static Builder builder(McpClient client) {
        return new Builder(client);
    }

    public McpClient client() {
        return client;
    }

    public String serverName() {
        return client.serverName();
    }

    /**
     * Initializes the client connection if needed, discovers remote tools, and populates bridged tools.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        if (!client.isConnected()) {
            client.initialize();
        }

        List<McpTool> remoteTools = client.listTools();
        bridgedTools.clear();

        for (McpTool remoteTool : remoteTools) {
            if (toolFilter.test(remoteTool.name())) {
                RiskLevel risk = riskResolver.apply(remoteTool.name());
                McpToolAdapter adapter = new McpToolAdapter(client, remoteTool, toolPrefix, risk, mapper);
                bridgedTools.put(adapter.definition().name(), adapter);
            }
        }

        initialized = true;
    }

    /**
     * Returns the list of bridged {@link Tool} instances.
     * Automatically initializes if not already initialized.
     */
    public List<Tool> getTools() {
        if (!initialized) {
            initialize();
        }
        return List.copyOf(bridgedTools.values());
    }

    /**
     * Finds a bridged tool by its SDK tool name.
     */
    public Tool findTool(String toolName) {
        if (!initialized) {
            initialize();
        }
        return bridgedTools.get(toolName);
    }

    @Override
    public void close() {
        bridgedTools.clear();
        initialized = false;
        client.close();
    }

    public static final class Builder {
        private final McpClient client;
        private String toolPrefix;
        private Predicate<String> toolFilter;
        private Function<String, RiskLevel> riskResolver;
        private ObjectMapper mapper;

        private Builder(McpClient client) {
            this.client = client;
        }

        public Builder toolPrefix(String prefix) {
            this.toolPrefix = prefix;
            return this;
        }

        public Builder includeOnly(Set<String> allowedNames) {
            if (allowedNames != null) {
                this.toolFilter = allowedNames::contains;
            }
            return this;
        }

        public Builder exclude(Set<String> excludedNames) {
            if (excludedNames != null) {
                this.toolFilter = name -> !excludedNames.contains(name);
            }
            return this;
        }

        public Builder filter(Predicate<String> filter) {
            this.toolFilter = filter;
            return this;
        }

        public Builder riskResolver(Function<String, RiskLevel> riskResolver) {
            this.riskResolver = riskResolver;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public McpServerBridge build() {
            return new McpServerBridge(this);
        }
    }
}
