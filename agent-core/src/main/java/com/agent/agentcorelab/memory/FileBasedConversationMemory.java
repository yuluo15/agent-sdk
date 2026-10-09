package com.agent.agentcorelab.memory;

import com.agent.agentcorelab.tool.ToolJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * File-backed persistent implementation of {@link ConversationMemory}.
 * Combines an in-memory LRU cache for millisecond-level execution with atomic disk-backed
 * JSON persistence, enabling conversations to survive server restarts.
 */
public final class FileBasedConversationMemory implements ConversationMemory {

    private static final System.Logger LOGGER = System.getLogger(FileBasedConversationMemory.class.getName());

    private final Path storageDir;
    private final InMemoryConversationMemory memoryCache;
    private final boolean failOnError;

    public FileBasedConversationMemory(Path storageDir) {
        this(storageDir, new InMemoryConversationMemory(), false);
    }

    public FileBasedConversationMemory(Path storageDir, boolean failOnError) {
        this(storageDir, new InMemoryConversationMemory(), failOnError);
    }

    public FileBasedConversationMemory(Path storageDir, InMemoryConversationMemory memoryCache) {
        this(storageDir, memoryCache, false);
    }

    public FileBasedConversationMemory(Path storageDir, InMemoryConversationMemory memoryCache, boolean failOnError) {
        this.storageDir = Objects.requireNonNull(storageDir, "storageDir cannot be null");
        this.memoryCache = Objects.requireNonNull(memoryCache, "memoryCache cannot be null");
        this.failOnError = failOnError;
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize conversation memory storage directory: " + storageDir, e);
        }
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public Path storageDir() {
        return storageDir;
    }

    private Path resolveFile(String conversationId) {
        // Sanitize conversation ID for safe file name
        String safeName = conversationId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
        return storageDir.resolve(safeName);
    }

    @Override
    public synchronized void append(String conversationId, ChatMessage message) {
        Objects.requireNonNull(conversationId, "conversationId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        // Ensure loaded from disk if not yet in memory
        ensureLoaded(conversationId);

        memoryCache.append(conversationId, message);
        flushToDisk(conversationId);
    }

    @Override
    public synchronized List<ChatMessage> getHistory(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        ensureLoaded(conversationId);
        return memoryCache.getHistory(conversationId);
    }

    @Override
    public synchronized void clear(String conversationId) {
        if (conversationId != null) {
            memoryCache.clear(conversationId);
            Path file = resolveFile(conversationId);
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to delete conversation memory file {0}: {1}", file, e.getMessage(), e);
                if (failOnError) {
                    throw new UncheckedIOException("Failed to delete conversation memory file: " + file, e);
                }
            }
        }
    }

    @Override
    public synchronized void compact(String conversationId) {
        if (conversationId == null) {
            return;
        }
        ensureLoaded(conversationId);
        memoryCache.compact(conversationId);
        flushToDisk(conversationId);
    }

    @Override
    public synchronized Optional<ChatMessage> getSummary(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        ensureLoaded(conversationId);
        return memoryCache.getSummary(conversationId);
    }

    @Override
    public synchronized int activeConversationCount() {
        return memoryCache.activeConversationCount();
    }

    private void ensureLoaded(String conversationId) {
        if (!memoryCache.getHistory(conversationId).isEmpty()) {
            return;
        }
        Path file = resolveFile(conversationId);
        if (Files.exists(file) && Files.isRegularFile(file)) {
            try {
                String json = Files.readString(file);
                JsonNode root = ToolJson.parse(json);
                if (root.isArray()) {
                    for (JsonNode item : root) {
                        String roleStr = item.path("role").asText("USER");
                        String content = item.path("content").asText("");
                        String tsStr = item.path("timestamp").asText(null);
                        Instant ts = tsStr != null ? Instant.parse(tsStr) : Instant.now();
                        ChatMessage.Role role = ChatMessage.Role.valueOf(roleStr);
                        memoryCache.append(conversationId, new ChatMessage(role, content, ts));
                    }
                }
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to load conversation memory from file {0}: {1}", file, e.getMessage(), e);
                if (failOnError) {
                    throw new IllegalStateException("Failed to load conversation memory from file: " + file, e);
                }
            }
        }
    }

    private void flushToDisk(String conversationId) {
        List<ChatMessage> history = memoryCache.getHistory(conversationId);
        Path file = resolveFile(conversationId);
        if (history.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to delete empty conversation memory file {0}: {1}", file, e.getMessage(), e);
                if (failOnError) {
                    throw new UncheckedIOException("Failed to delete empty conversation memory file: " + file, e);
                }
            }
            return;
        }

        List<MessageRecord> records = new ArrayList<>(history.size());
        for (ChatMessage msg : history) {
            records.add(new MessageRecord(msg.role().name(), msg.content(), msg.timestamp().toString()));
        }

        try {
            String json = ToolJson.write(records);
            Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tempFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tempFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "Failed to flush conversation history to disk for conversationId {0}: {1}", conversationId, e.getMessage(), e);
            if (failOnError) {
                throw new UncheckedIOException("Failed to flush conversation memory to disk: " + file, e);
            }
        }
    }

    private record MessageRecord(String role, String content, String timestamp) {}
}
