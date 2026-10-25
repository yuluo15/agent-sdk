package com.agent.agentcorelab.checkpoint;

import com.agent.agentcorelab.agent.AgentSpec;
import com.agent.agentcorelab.agent.CompletionCheck;
import com.agent.agentcorelab.agent.approval.ApprovalBinding;
import com.agent.agentcorelab.agent.context.TrustedContext;
import com.agent.agentcorelab.agent.plan.TaskBrief;
import com.agent.agentcorelab.agent.plan.TaskPlan;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.PendingQuestion;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.Workspace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Standard JDBC reference implementation of {@link CheckpointSpi} with optimistic locking.
 * <p>
 * Employs row-level version checking ({@code UPDATE ... WHERE run_id = ? AND version = ?})
 * to provide atomic fencing against concurrent updates or split-brain writes.
 */
public final class JdbcCheckpointStore implements CheckpointSpi {

    public static final String DEFAULT_TABLE = "agent_checkpoints";

    private final DataSource dataSource;
    private final String tableName;
    private final Function<String, AgentSpec> specProvider;
    private final ObjectMapper mapper = ToolJson.mapper();

    public JdbcCheckpointStore(DataSource dataSource, String tableName, Function<String, AgentSpec> specProvider) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = tableName != null && !tableName.isBlank() ? tableName : DEFAULT_TABLE;
        this.specProvider = specProvider;
    }

    public JdbcCheckpointStore(DataSource dataSource, Function<String, AgentSpec> specProvider) {
        this(dataSource, DEFAULT_TABLE, specProvider);
    }

    public JdbcCheckpointStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE, null);
    }

    /**
     * Initializes the table schema if it does not already exist.
     */
    public void initSchema() {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "run_id VARCHAR(36) PRIMARY KEY, "
                + "version BIGINT NOT NULL, "
                + "status VARCHAR(32) NOT NULL, "
                + "agent_id VARCHAR(64) NOT NULL, "
                + "payload_json CLOB NOT NULL, "
                + "updated_at TIMESTAMP NOT NULL"
                + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.execute();
        } catch (SQLException e) {
            // Some databases might throw if table exists or use different syntax
            if (!e.getMessage().toLowerCase().contains("already exists")) {
                throw new IllegalStateException("Failed to initialize checkpoint table: " + tableName, e);
            }
        }
    }

    @Override
    public void save(RunCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String payloadJson = serializeCheckpoint(checkpoint);
        Timestamp now = Timestamp.from(Instant.now());

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Check existing version
                Long existingVersion = null;
                String selectSql = "SELECT version FROM " + tableName + " WHERE run_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setString(1, checkpoint.runId().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existingVersion = rs.getLong("version");
                        }
                    }
                }

                if (existingVersion == null) {
                    // Insert new checkpoint
                    String insertSql = "INSERT INTO " + tableName + " (run_id, version, status, agent_id, payload_json, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setString(1, checkpoint.runId().toString());
                        ps.setLong(2, checkpoint.version());
                        ps.setString(3, checkpoint.status().name());
                        ps.setString(4, checkpoint.agentId());
                        ps.setString(5, payloadJson);
                        ps.setTimestamp(6, now);
                        ps.executeUpdate();
                    }
                } else {
                    // Check version
                    if (checkpoint.version() <= existingVersion) {
                        throw new CheckpointConcurrencyException(checkpoint.runId(), existingVersion, checkpoint.version());
                    }

                    // Update with optimistic lock
                    String updateSql = "UPDATE " + tableName + " SET version = ?, status = ?, agent_id = ?, payload_json = ?, updated_at = ? "
                            + "WHERE run_id = ? AND version = ?";
                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setLong(1, checkpoint.version());
                        ps.setString(2, checkpoint.status().name());
                        ps.setString(3, checkpoint.agentId());
                        ps.setString(4, payloadJson);
                        ps.setTimestamp(5, now);
                        ps.setString(6, checkpoint.runId().toString());
                        ps.setLong(7, existingVersion);
                        int affected = ps.executeUpdate();
                        if (affected == 0) {
                            throw new CheckpointConcurrencyException(checkpoint.runId(), existingVersion, checkpoint.version());
                        }
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof CheckpointConcurrencyException cce) {
                    throw cce;
                }
                throw new IllegalStateException("Failed to persist checkpoint for runId: " + checkpoint.runId(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while persisting checkpoint", e);
        }
    }

    @Override
    public Optional<RunCheckpoint> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        String sql = "SELECT payload_json FROM " + tableName + " WHERE run_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("payload_json");
                    return Optional.ofNullable(deserializeCheckpoint(json));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while querying checkpoint for runId: " + runId, e);
        }
        return Optional.empty();
    }

    @Override
    public void remove(UUID runId) {
        if (runId == null) {
            return;
        }
        String sql = "DELETE FROM " + tableName + " WHERE run_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, runId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while removing checkpoint for runId: " + runId, e);
        }
    }

    @Override
    public List<RunCheckpoint> findWaiting() {
        List<RunCheckpoint> list = new ArrayList<>();
        String sql = "SELECT payload_json FROM " + tableName;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String json = rs.getString("payload_json");
                RunCheckpoint cp = deserializeCheckpoint(json);
                if (cp != null) {
                    list.add(cp);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Database error while listing waiting checkpoints", e);
        }
        return list;
    }

    private String serializeCheckpoint(RunCheckpoint cp) {
        Map<String, Object> map = new HashMap<>();
        map.put("runId", cp.runId().toString());
        map.put("version", cp.version());
        map.put("status", cp.status().name());
        map.put("timestamp", cp.timestamp().toString());
        map.put("agentId", cp.agentId());
        map.put("goal", cp.goal());
        map.put("message", cp.message());
        map.put("turns", cp.clarificationSubDialogueTurns());
        map.put("corrections", cp.corrections());
        map.put("metadata", cp.metadata());

        // Observations
        if (!cp.observations().isEmpty()) {
            List<Map<String, Object>> obsList = new ArrayList<>();
            for (RunSnapshot.Observation obs : cp.observations()) {
                Map<String, Object> oMap = new HashMap<>();
                oMap.put("callId", obs.call().callId());
                oMap.put("name", obs.call().name());
                oMap.put("args", obs.call().argumentsJson());
                if (obs.outcome() instanceof com.agent.agentcorelab.tool.ToolOutcome.Success s) {
                    oMap.put("outcomeType", "SUCCESS");
                    oMap.put("outputJson", s.outputJson());
                } else if (obs.outcome() instanceof com.agent.agentcorelab.tool.ToolOutcome.Failure f) {
                    oMap.put("outcomeType", "FAILURE");
                    oMap.put("code", f.code());
                    oMap.put("error", f.message());
                    oMap.put("terminal", f.terminal());
                }
                obsList.add(oMap);
            }
            map.put("observations", obsList);
        }

        // Workspace
        Workspace ws = cp.request().workspace();
        map.put("inPath", ws.inputRoot().toAbsolutePath().toString());
        map.put("outPath", ws.outputRoot().toAbsolutePath().toString());
        map.put("tracePath", ws.traceRoot().toAbsolutePath().toString());

        // Request
        cp.request().conversationId().ifPresent(c -> map.put("conversationId", c));
        cp.request().userId().ifPresent(u -> map.put("userId", u));

        // TrustedContext
        TrustedContext tc = cp.trustedContext();
        if (tc != null) {
            map.put("tc_tenantId", tc.tenantId());
            map.put("tc_userPrincipal", tc.userPrincipal());
            map.put("tc_agentIdentity", tc.agentIdentity());
            map.put("tc_scopes", new ArrayList<>(tc.scopes()));
            if (tc.traceId() != null) map.put("tc_traceId", tc.traceId());
            if (tc.requestId() != null) map.put("tc_requestId", tc.requestId());
        }

        // Pending Question
        if (cp.pendingQuestion() != null) {
            map.put("pq_callId", cp.pendingQuestion().callId());
            map.put("pq_question", cp.pendingQuestion().question());
            map.put("pq_options", cp.pendingQuestion().options());
        }

        // Pending Approval
        if (cp.pendingApproval() != null) {
            ApprovalRequest ar = cp.pendingApproval();
            map.put("ar_approvalId", ar.approvalId());
            map.put("ar_callId", ar.callId());
            map.put("ar_toolName", ar.toolName());
            map.put("ar_argumentsJson", ar.argumentsJson());
            map.put("ar_description", ar.description());
            if (ar.requestedAt() != null) {
                map.put("ar_requestedAt", ar.requestedAt().toString());
            }
            map.put("ar_requiredRole", ar.requiredRole());
            map.put("ar_policyVersion", ar.policyVersion());
        }

        // Pending Binding
        if (cp.pendingBinding() != null) {
            ApprovalBinding ab = cp.pendingBinding();
            map.put("ab_approvalId", ab.approvalId().toString());
            map.put("ab_tenantId", ab.tenantId());
            map.put("ab_requesterPrincipal", ab.requesterPrincipal());
            map.put("ab_requiredRole", ab.requiredRole());
            map.put("ab_toolName", ab.toolName());
            map.put("ab_callId", ab.callId());
            map.put("ab_argumentsSha256", ab.argumentsSha256());
            map.put("ab_policyVersion", ab.policyVersion());
            map.put("ab_expiresAt", ab.expiresAt().toString());
            map.put("ab_consumed", ab.isConsumed());
        }

        // Pending Tool Calls
        if (cp.pendingHighRiskCall() != null) {
            map.put("phc_callId", cp.pendingHighRiskCall().callId());
            map.put("phc_name", cp.pendingHighRiskCall().name());
            map.put("phc_args", cp.pendingHighRiskCall().argumentsJson());
        }
        map.put("pendingHighRiskIndex", cp.pendingHighRiskIndex());

        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize checkpoint payload", e);
        }
    }

    private RunCheckpoint deserializeCheckpoint(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(json);
            UUID runId = UUID.fromString(root.path("runId").asText());
            long version = root.path("version").asLong(1);
            RunStatus status = RunStatus.valueOf(root.path("status").asText());
            Instant timestamp = Instant.parse(root.path("timestamp").asText());
            String agentId = root.path("agentId").asText();
            String goal = root.path("goal").asText();
            String message = root.path("message").asText("");
            int turns = root.path("turns").asInt(0);
            int corrections = root.path("corrections").asInt(0);

            Map<String, String> metadata = root.has("metadata")
                    ? mapper.convertValue(root.get("metadata"), new TypeReference<Map<String, String>>() {})
                    : Map.of();

            Path inPath = Path.of(root.path("inPath").asText());
            Path outPath = Path.of(root.path("outPath").asText());
            Path tracePath = Path.of(root.path("tracePath").asText());
            Workspace ws = new Workspace(inPath, outPath, tracePath);

            String conversationId = root.hasNonNull("conversationId") ? root.get("conversationId").asText() : null;
            String userId = root.hasNonNull("userId") ? root.get("userId").asText() : null;

            TrustedContext tc = null;
            if (root.has("tc_tenantId")) {
                Set<String> scopes = root.has("tc_scopes")
                        ? mapper.convertValue(root.get("tc_scopes"), new TypeReference<Set<String>>() {})
                        : Set.of();
                tc = new TrustedContext(
                        root.path("tc_tenantId").asText("default"),
                        root.path("tc_userPrincipal").asText("anonymous"),
                        root.path("tc_agentIdentity").asText("agent"),
                        scopes,
                        root.hasNonNull("tc_traceId") ? root.get("tc_traceId").asText() : null,
                        root.hasNonNull("tc_requestId") ? root.get("tc_requestId").asText() : null
                );
            }

            RunRequest request = new RunRequest(goal, ws, () -> false, Optional.empty(),
                    Optional.ofNullable(conversationId), Optional.ofNullable(userId), Optional.ofNullable(tc));

            PendingQuestion pq = null;
            if (root.has("pq_questionId")) {
                List<String> options = root.has("pq_options")
                        ? mapper.convertValue(root.get("pq_options"), new TypeReference<List<String>>() {})
                        : List.of();
                pq = new PendingQuestion(root.path("pq_callId").asText(), root.path("pq_question").asText(), options);
            }

            ApprovalRequest ar = null;
            if (root.has("ar_approvalId")) {
                Instant reqAt = root.has("ar_requestedAt")
                        ? Instant.parse(root.path("ar_requestedAt").asText())
                        : Instant.now();
                ar = new ApprovalRequest(
                        root.path("ar_approvalId").asText(),
                        root.path("ar_callId").asText(),
                        root.path("ar_toolName").asText(),
                        root.path("ar_argumentsJson").asText(),
                        root.path("ar_description").asText(""),
                        reqAt,
                        root.path("ar_policyVersion").asText("1.0"),
                        root.path("ar_requiredRole").asText("OPERATOR")
                );
            }

            ApprovalBinding ab = null;
            if (root.has("ab_approvalId")) {
                ab = new ApprovalBinding(
                        UUID.fromString(root.path("ab_approvalId").asText()),
                        root.path("ab_tenantId").asText(),
                        root.path("ab_requesterPrincipal").asText(),
                        root.path("ab_requiredRole").asText(),
                        root.path("ab_toolName").asText(),
                        root.path("ab_callId").asText(),
                        root.path("ab_argumentsSha256").asText(),
                        root.path("ab_policyVersion").asText(),
                        Instant.parse(root.path("ab_expiresAt").asText())
                );
            }

            ToolCall phc = null;
            if (root.has("phc_callId")) {
                phc = new ToolCall(root.path("phc_callId").asText(), root.path("phc_name").asText(), root.path("phc_args").asText());
            }
            int pendingHighRiskIndex = root.path("pendingHighRiskIndex").asInt(0);

            List<RunSnapshot.Observation> observations = new ArrayList<>();
            if (root.has("observations") && root.get("observations").isArray()) {
                for (JsonNode oNode : root.get("observations")) {
                    String cId = oNode.path("callId").asText();
                    String cName = oNode.path("name").asText();
                    String cArgs = oNode.path("args").asText();
                    ToolCall obsCall = new ToolCall(cId, cName, cArgs);
                    String oType = oNode.path("outcomeType").asText("SUCCESS");
                    com.agent.agentcorelab.tool.ToolOutcome outcome;
                    if ("FAILURE".equalsIgnoreCase(oType)) {
                        outcome = new com.agent.agentcorelab.tool.ToolOutcome.Failure(
                                oNode.path("code").asText("ERROR"),
                                oNode.path("error").asText(),
                                oNode.path("terminal").asBoolean(false)
                        );
                    } else {
                        outcome = new com.agent.agentcorelab.tool.ToolOutcome.Success(oNode.path("outputJson").asText());
                    }
                    observations.add(new RunSnapshot.Observation(obsCall, outcome));
                }
            }

            AgentSpec spec = specProvider != null ? specProvider.apply(agentId) : null;
            if (spec == null) {
                spec = new AgentSpec(agentId, "Restored instructions for " + agentId, Set.of(),
                        new RunLimits(20, 40, Duration.ofSeconds(10), Duration.ofSeconds(20),
                                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15)),
                        (s, a) -> CompletionCheck.Result.satisfied(),
                        ExecutionPolicy.strict());
            }

            return new RunCheckpoint(
                    runId,
                    version,
                    status,
                    timestamp,
                    agentId,
                    spec,
                    goal,
                    request,
                    TaskBrief.of(goal),
                    TaskPlan.empty(1),
                    observations,
                    List.of(),
                    pq,
                    ar,
                    ab,
                    phc,
                    phc != null ? List.of(phc) : List.of(),
                    pendingHighRiskIndex,
                    tc,
                    new RunMetrics(0, 0, Duration.ZERO, Optional.empty()),
                    turns,
                    corrections,
                    message,
                    metadata
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize checkpoint payload", e);
        }
    }
}
