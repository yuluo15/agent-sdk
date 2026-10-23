package com.agent.agentcorelab.mcp.transport;

import com.agent.agentcorelab.mcp.protocol.JsonRpcError;
import com.agent.agentcorelab.mcp.protocol.JsonRpcMessage;
import com.agent.agentcorelab.mcp.protocol.JsonRpcNotification;
import com.agent.agentcorelab.mcp.protocol.JsonRpcRequest;
import com.agent.agentcorelab.mcp.protocol.JsonRpcResponse;
import com.agent.agentcorelab.mcp.protocol.McpJsonRpcCodec;
import com.agent.agentcorelab.mcp.protocol.McpProtocolConstants;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Production-grade Stdio-based process transport for MCP.
 * <p>
 * Key enterprise capabilities:
 * 1. Java 21 Virtual Threads: Non-blocking line-based streaming I/O over stdout/stderr.
 * 2. Process Watchdog & Zombie Prevention: Recursively destroys all child processes on JVM exit or close.
 * 3. Atomic Request-Response Matching: Concurrent correlation with per-call timeout safety.
 */
public final class StdioProcessTransport implements McpTransport {

    private final String serverName;
    private final List<String> command;
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private final Duration defaultTimeout;

    private final Map<String, CompletableFuture<JsonRpcResponse>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private Process process;
    private BufferedWriter writer;
    private Consumer<JsonRpcMessage> incomingMessageHandler;
    private volatile boolean closed = false;
    private Thread shutdownHook;

    public StdioProcessTransport(String serverName, List<String> command) {
        this(serverName, command, Map.of(), null, Duration.ofSeconds(30));
    }

    public StdioProcessTransport(String serverName, List<String> command, Map<String, String> environment,
                                 Path workingDirectory, Duration defaultTimeout) {
        this.serverName = Objects.requireNonNull(serverName, "serverName cannot be null");
        this.command = List.copyOf(Objects.requireNonNull(command, "command cannot be null"));
        if (this.command.isEmpty()) {
            throw new IllegalArgumentException("command list cannot be empty");
        }
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
        this.workingDirectory = workingDirectory;
        this.defaultTimeout = defaultTimeout != null ? defaultTimeout : Duration.ofSeconds(30);
    }

    @Override
    public synchronized void start(Consumer<JsonRpcMessage> incomingMessageHandler) {
        if (process != null && process.isAlive()) {
            throw new IllegalStateException("Transport already started for server: " + serverName);
        }
        this.incomingMessageHandler = incomingMessageHandler;
        this.closed = false;

        ProcessBuilder pb = new ProcessBuilder(command);
        if (!environment.isEmpty()) {
            pb.environment().putAll(environment);
        }
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }

        try {
            this.process = pb.start();
            this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch MCP server process [" + serverName + "]: " + String.join(" ", command), e);
        }

        // Register watchdog shutdown hook to prevent zombie processes
        this.shutdownHook = new Thread(this::destroyProcessRecursively, "mcp-watchdog-" + serverName);
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Start Virtual Thread readers for stdout & stderr
        Thread.ofVirtual().name("mcp-stdio-out-" + serverName).start(this::readStdoutLoop);
        Thread.ofVirtual().name("mcp-stdio-err-" + serverName).start(this::readStderrLoop);
    }

    private void readStdoutLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonRpcMessage msg = McpJsonRpcCodec.decode(line);
                    if (msg instanceof JsonRpcResponse resp) {
                        CompletableFuture<JsonRpcResponse> future = pending.remove(resp.id());
                        if (future != null) {
                            future.complete(resp);
                        }
                    }
                    if (incomingMessageHandler != null) {
                        incomingMessageHandler.accept(msg);
                    }
                } catch (Exception e) {
                    // Ignore non-fatal malformed frames from logging output
                }
            }
        } catch (IOException ignored) {
            // Stream closed
        } finally {
            if (!closed) {
                close();
            }
        }
    }

    private void readStderrLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                // MCP server diagnostic logging output (silently consumed or forwarded to trace)
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public CompletableFuture<JsonRpcResponse> send(JsonRpcRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        if (closed || process == null || !process.isAlive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MCP transport is not connected for: " + serverName));
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        pending.put(request.id(), future);

        // Schedule timeout protection
        CompletableFuture<JsonRpcResponse> timeoutFuture = future.orTimeout(defaultTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((res, ex) -> {
                    pending.remove(request.id());
                    if (ex instanceof TimeoutException) {
                        // Request timed out
                    }
                });

        try {
            String json = McpJsonRpcCodec.encode(request);
            synchronized (writeLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            pending.remove(request.id());
            future.completeExceptionally(new IllegalStateException("Failed to write to MCP stdin: " + e.getMessage(), e));
        }

        return timeoutFuture;
    }

    @Override
    public void sendNotification(JsonRpcNotification notification) {
        Objects.requireNonNull(notification, "notification cannot be null");
        if (closed || process == null || !process.isAlive()) {
            return;
        }

        try {
            String json = McpJsonRpcCodec.encode(notification);
            synchronized (writeLock) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public boolean isAlive() {
        return !closed && process != null && process.isAlive();
    }

    @Override
    public Optional<ProcessHandle> processHandle() {
        return process != null ? Optional.of(process.toHandle()) : Optional.empty();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }

        // Cancel all pending RPC calls
        pending.forEach((id, f) -> f.completeExceptionally(new IllegalStateException("MCP Server process terminated: " + serverName)));
        pending.clear();

        destroyProcessRecursively();
    }

    private void destroyProcessRecursively() {
        if (process != null) {
            try {
                // Recursively kill child processes first (e.g. node, python sub-workers)
                process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    public static Builder builder(String command) {
        return new Builder("mcp-server", command);
    }

    public static Builder builder(String serverName, String command) {
        return new Builder(serverName, command);
    }

    public static final class Builder {
        private String serverName;
        private final List<String> command = new ArrayList<>();
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Path workingDirectory;
        private Duration timeout = Duration.ofSeconds(30);

        private Builder(String serverName, String command) {
            this.serverName = serverName;
            this.command.add(command);
        }

        public Builder serverName(String serverName) {
            this.serverName = serverName;
            return this;
        }

        public Builder arg(String arg) {
            this.command.add(arg);
            return this;
        }

        public Builder args(List<String> args) {
            if (args != null) {
                this.command.addAll(args);
            }
            return this;
        }

        public Builder environment(Map<String, String> env) {
            if (env != null) {
                this.environment.putAll(env);
            }
            return this;
        }

        public Builder workingDirectory(Path path) {
            this.workingDirectory = path;
            return this;
        }

        public Builder workingDirectory(File file) {
            this.workingDirectory = file != null ? file.toPath() : null;
            return this;
        }

        public Builder startupTimeout(Duration timeout) {
            this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
            return this;
        }

        public StdioProcessTransport build() {
            return new StdioProcessTransport(serverName, command, environment, workingDirectory, timeout);
        }
    }
}
