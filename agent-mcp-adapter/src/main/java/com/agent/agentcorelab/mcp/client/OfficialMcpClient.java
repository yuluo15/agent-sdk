package com.agent.agentcorelab.mcp.client;

import com.agent.agentcorelab.mcp.protocol.McpContent;
import com.agent.agentcorelab.mcp.protocol.McpInitializeResult;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpTool;
import com.agent.agentcorelab.mcp.protocol.McpToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standard enterprise {@link McpClient} implementation backed by the official
 * Model Context Protocol Java SDK ({@code io.modelcontextprotocol.sdk:mcp}).
 * <p>
 * Supports both local OS process communication (Stdio) and remote HTTP/SSE streaming,
 * with standard OAuth/Bearer token authorization, custom headers, and schema negotiation.
 */
public final class OfficialMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(OfficialMcpClient.class);

    private final String serverName;
    private final McpSyncClient syncClient;
    private final McpClientTransport transport;
    private final ObjectMapper mapper;

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile McpInitializeResult cachedInitializeResult;

    public OfficialMcpClient(String serverName, McpSyncClient syncClient) {
        this(serverName, syncClient, null, McpJsonRpcCodec.mapper());
    }

    public OfficialMcpClient(String serverName, McpSyncClient syncClient, McpClientTransport transport, ObjectMapper mapper) {
        this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
        this.syncClient = Objects.requireNonNull(syncClient, "syncClient cannot be null");
        this.transport = transport;
        this.mapper = mapper != null ? mapper : McpJsonRpcCodec.mapper();
    }

    public static Builder builder(String serverName) {
        return new Builder(serverName);
    }

    /**
     * Creates an OfficialMcpClient connected to a local OS subprocess via standard input/output.
     */
    public static OfficialMcpClient stdio(String serverName, String command, List<String> args, Map<String, String> env) {
        return builder(serverName).stdio(command, args, env).build();
    }

    /**
     * Creates an OfficialMcpClient connected to a remote MCP server via HTTP/SSE.
     */
    public static OfficialMcpClient sse(String serverName, String baseUrl) {
        return builder(serverName).sse(baseUrl).build();
    }

    /**
     * Creates an OfficialMcpClient connected to a remote MCP server via HTTP/SSE with authorization and custom headers.
     */
    public static OfficialMcpClient sse(String serverName, String baseUrl, String sseEndpoint, String authToken, Map<String, String> headers) {
        return builder(serverName)
                .sse(baseUrl, sseEndpoint)
                .authorization(authToken)
                .headers(headers)
                .build();
    }

    /**
     * Wraps an official {@link McpClientTransport} into an {@link OfficialMcpClient}.
     */
    public static OfficialMcpClient from(String serverName, McpClientTransport transport) {
        return builder(serverName).transport(transport).build();
    }

    /**
     * Wraps an already built official {@link McpSyncClient} into an {@link OfficialMcpClient}.
     */
    public static OfficialMcpClient from(String serverName, McpSyncClient syncClient) {
        return new OfficialMcpClient(serverName, syncClient);
    }

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public McpInitializeResult initialize() {
        if (initialized.get() && cachedInitializeResult != null) {
            return cachedInitializeResult;
        }

        try {
            McpSchema.InitializeResult result = syncClient.initialize();
            JsonNode capsNode = result.capabilities() != null
                    ? mapper.valueToTree(result.capabilities())
                    : mapper.createObjectNode();
            McpInitializeResult.ServerInfo serverInfo = result.serverInfo() != null
                    ? new McpInitializeResult.ServerInfo(result.serverInfo().name(), result.serverInfo().version())
                    : new McpInitializeResult.ServerInfo("unknown", "unknown");

            this.cachedInitializeResult = new McpInitializeResult(result.protocolVersion(), capsNode, serverInfo);
            this.initialized.set(true);
            log.info("Initialized Official MCP Client [{}] successfully with protocol [{}]", serverName, result.protocolVersion());
            return cachedInitializeResult;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Official MCP Client [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<McpInitializeResult> initializeAsync() {
        return CompletableFuture.supplyAsync(this::initialize);
    }

    @Override
    public List<McpTool> listTools() {
        ensureConnected();
        try {
            McpSchema.ListToolsResult listResult = syncClient.listTools();
            if (listResult == null || listResult.tools() == null) {
                return List.of();
            }

            List<McpTool> tools = new ArrayList<>(listResult.tools().size());
            for (McpSchema.Tool tool : listResult.tools()) {
                JsonNode inputSchema = tool.inputSchema() != null
                        ? mapper.valueToTree(tool.inputSchema())
                        : mapper.createObjectNode().put("type", "object");
                tools.add(new McpTool(tool.name(), tool.description() != null ? tool.description() : "", inputSchema));
            }
            return List.copyOf(tools);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list tools from MCP server [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public CompletableFuture<List<McpTool>> listToolsAsync() {
        return CompletableFuture.supplyAsync(this::listTools);
    }

    @Override
    public McpToolResult callTool(String toolName, JsonNode arguments) {
        ensureConnected();
        Map<String, Object> argsMap = null;
        if (arguments != null && !arguments.isNull()) {
            if (arguments.isObject()) {
                try {
                    argsMap = mapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    return McpToolResult.failure("Invalid tool arguments: " + e.getMessage());
                }
            }
        }
        if (argsMap == null) {
            argsMap = Map.of();
        }

        try {
            McpSchema.CallToolRequest req = new McpSchema.CallToolRequest(toolName, argsMap);
            McpSchema.CallToolResult resp = syncClient.callTool(req);
            return convertCallToolResult(resp);
        } catch (Exception e) {
            log.warn("MCP callTool failed for [{}] on server [{}]: {}", toolName, serverName, e.getMessage());
            return McpToolResult.failure("MCP tool call error: " + e.getMessage());
        }
    }

    @Override
    public CompletableFuture<McpToolResult> callToolAsync(String toolName, JsonNode arguments) {
        return CompletableFuture.supplyAsync(() -> callTool(toolName, arguments));
    }

    @Override
    public Duration ping() {
        ensureConnected();
        Instant start = Instant.now();
        try {
            syncClient.ping();
            return Duration.between(start, Instant.now());
        } catch (Exception e) {
            throw new IllegalStateException("Ping failed to MCP server [" + serverName + "]: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return initialized.get() && syncClient != null && syncClient.isInitialized();
    }

    @Override
    public Optional<ProcessHandle> processHandle() {
        if (transport instanceof StdioClientTransport) {
            try {
                Field f = StdioClientTransport.class.getDeclaredField("process");
                f.setAccessible(true);
                Process p = (Process) f.get(transport);
                if (p != null) {
                    return Optional.of(p.toHandle());
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        initialized.set(false);
        if (syncClient != null) {
            try {
                syncClient.close();
            } catch (Exception e) {
                log.debug("Error while closing MCP syncClient [{}]: {}", serverName, e.getMessage());
            }
        }
    }

    public McpSyncClient officialSyncClient() {
        return syncClient;
    }

    public McpClientTransport officialTransport() {
        return transport;
    }

    private void ensureConnected() {
        if (!initialized.get() || syncClient == null || !syncClient.isInitialized()) {
            throw new IllegalStateException("OfficialMcpClient [" + serverName + "] is not initialized. Please call initialize() first.");
        }
    }

    private McpToolResult convertCallToolResult(McpSchema.CallToolResult resp) {
        if (resp == null) {
            return McpToolResult.success("");
        }
        boolean isError = Boolean.TRUE.equals(resp.isError());
        List<McpContent> contents = new ArrayList<>();
        if (resp.content() != null) {
            for (McpSchema.Content c : resp.content()) {
                if (c instanceof McpSchema.TextContent tc) {
                    contents.add(new McpContent(McpContent.TYPE_TEXT, tc.text(), null, null, null));
                } else if (c instanceof McpSchema.ImageContent ic) {
                    contents.add(new McpContent(McpContent.TYPE_IMAGE, null, ic.data(), ic.mimeType(), null));
                } else if (c instanceof McpSchema.AudioContent ac) {
                    contents.add(new McpContent("audio", null, ac.data(), ac.mimeType(), null));
                } else if (c instanceof McpSchema.ResourceContent rc) {
                    contents.add(new McpContent(McpContent.TYPE_RESOURCE, null, null, rc.mimeType(), rc.uri()));
                } else {
                    contents.add(new McpContent(c.type() != null ? c.type() : McpContent.TYPE_TEXT, c.toString(), null, null, null));
                }
            }
        }
        return new McpToolResult(contents, isError);
    }

    public static final class Builder {
        private final String serverName;
        private McpClientTransport transport;
        private String clientName = "agent-sdk";
        private String clientVersion = "1.1.0";
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration initializationTimeout = Duration.ofSeconds(30);
        private ObjectMapper mapper;

        // Stdio
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();

        // SSE
        private String baseUrl;
        private String sseEndpoint = "/sse";
        private String authToken;
        private Map<String, String> headers = new LinkedHashMap<>();
        private Duration connectTimeout = Duration.ofSeconds(10);

        private Builder(String serverName) {
            this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
        }

        public Builder clientName(String clientName) {
            this.clientName = clientName;
            return this;
        }

        public Builder clientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder initializationTimeout(Duration initializationTimeout) {
            this.initializationTimeout = initializationTimeout;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public Builder transport(McpClientTransport transport) {
            this.transport = transport;
            return this;
        }

        public Builder stdio(String command, List<String> args, Map<String, String> env) {
            this.command = command;
            if (args != null) {
                this.args = new ArrayList<>(args);
            }
            if (env != null) {
                this.env = new LinkedHashMap<>(env);
            }
            return this;
        }

        public Builder sse(String baseUrl) {
            return sse(baseUrl, "/sse");
        }

        public Builder sse(String baseUrl, String sseEndpoint) {
            this.baseUrl = baseUrl;
            this.sseEndpoint = (sseEndpoint != null && !sseEndpoint.isBlank()) ? sseEndpoint : "/sse";
            return this;
        }

        public Builder authorization(String authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public OfficialMcpClient build() {
            ObjectMapper effectiveMapper = mapper != null ? mapper : McpJsonRpcCodec.mapper();
            McpClientTransport effectiveTransport = transport;

            if (effectiveTransport == null) {
                if (command != null && !command.isBlank()) {
                    ServerParameters params = ServerParameters.builder(command)
                            .args(args)
                            .env(env)
                            .build();
                    effectiveTransport = new StdioClientTransport(params, new JacksonMcpJsonMapper(effectiveMapper));
                } else if (baseUrl != null && !baseUrl.isBlank()) {
                    HttpClientSseClientTransport.Builder sseBuilder = HttpClientSseClientTransport.builder(baseUrl)
                            .sseEndpoint(sseEndpoint)
                            .jsonMapper(new JacksonMcpJsonMapper(effectiveMapper))
                            .connectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(10));

                    if (authToken != null && !authToken.isBlank()) {
                        String authHeader = authToken.toLowerCase().startsWith("bearer ") ? authToken : "Bearer " + authToken;
                        sseBuilder.customizeRequest(req -> req.header("Authorization", authHeader));
                    }
                    if (headers != null && !headers.isEmpty()) {
                        headers.forEach((k, v) -> sseBuilder.customizeRequest(req -> req.header(k, v)));
                    }
                    effectiveTransport = sseBuilder.build();
                } else {
                    throw new IllegalStateException("Must configure either an explicit transport, stdio command, or sse baseUrl for MCP client [" + serverName + "]");
                }
            }

            McpSchema.ClientCapabilities capabilities = McpSchema.ClientCapabilities.builder()
                    .roots(true)
                    .build();

            McpSchema.Implementation clientInfo = new McpSchema.Implementation(clientName, clientVersion);

            McpSyncClient client = io.modelcontextprotocol.client.McpClient.sync(effectiveTransport)
                    .clientInfo(clientInfo)
                    .capabilities(capabilities)
                    .requestTimeout(requestTimeout != null ? requestTimeout : Duration.ofSeconds(30))
                    .initializationTimeout(initializationTimeout != null ? initializationTimeout : Duration.ofSeconds(30))
                    .jsonSchemaValidator((schema, payload) -> io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse.asValid(""))
                    .build();

            return new OfficialMcpClient(serverName, client, effectiveTransport, effectiveMapper);
        }
    }
}
