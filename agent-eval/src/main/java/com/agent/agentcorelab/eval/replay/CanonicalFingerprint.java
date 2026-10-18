package com.agent.agentcorelab.eval.replay;

import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.tool.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

/**
 * Computes deterministic canonical request fingerprints conforming to Phase 0 Replay Protocol.
 * <p>
 * Dynamic attributes such as runId, timestamp, and approvalId are strictly excluded from
 * the fingerprint calculation to prevent false replay misses across runs.
 * </p>
 */
public final class CanonicalFingerprint {

    private CanonicalFingerprint() {
    }

    /**
     * Computes a SHA-256 hash string representing the canonical fingerprint of the LLM request.
     */
    public static String compute(
            String systemPrompt,
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            String modelName,
            Double temperature,
            Integer maxTokens
    ) {
        StringBuilder sb = new StringBuilder();

        // 1. Canonical system prompt
        sb.append("SYS:").append(normalize(systemPrompt)).append("\n");

        // 2. Canonical message history
        sb.append("MSGS:\n");
        if (messages != null) {
            for (ChatMessage msg : messages) {
                if (msg != null) {
                    sb.append(msg.role().name()).append(":").append(normalize(msg.content())).append("\n");
                }
            }
        }

        // 3. Canonical tool schemas (sorted alphabetically by tool name for determinism)
        sb.append("TOOLS:\n");
        if (tools != null) {
            List<ToolDefinition> sortedTools = tools.stream()
                    .filter(t -> t != null && t.name() != null)
                    .sorted(Comparator.comparing(ToolDefinition::name))
                    .toList();
            for (ToolDefinition tool : sortedTools) {
                sb.append(tool.name()).append(":").append(normalize(tool.inputSchema())).append("\n");
            }
        }

        // 4. Model parameters
        sb.append("PARAMS:").append(modelName == null ? "default" : modelName)
                .append("|temp=").append(temperature == null ? "0.0" : String.format("%.2f", temperature))
                .append("|max=").append(maxTokens == null ? "default" : maxTokens);

        return sha256Hex(sb.toString());
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r\n", "\n").trim();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not supported by JVM", e);
        }
    }
}
