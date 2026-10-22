package com.agent.agentcorelab.bootstrap.logging;

import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.trace.RunEventListener;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Production-grade SLF4J logging listener that formats Agent execution events into clear,
 * structured, traceable log entries with explicit root-cause attribution markers.
 * <p>
 * Attribution markers allow developers and operators to instantly distinguish between:
 * <ul>
 *     <li>{@code [USER_TOOL_ERROR]}: The host application's business tool returned a business failure or threw an exception</li>
 *     <li>{@code [MODEL_GATEWAY_ERROR]}: The external LLM service encountered an HTTP timeout, rate limit (429), or network error</li>
 *     <li>{@code [BUDGET_EXHAUSTED]}: The run was safely stopped because it reached model request, tool call, or timeout budgets</li>
 *     <li>{@code [HITL_APPROVAL_REQUIRED]}: A high-risk operation was intercepted, requiring human authorization</li>
 *     <li>{@code [SDK_INTERNAL_ERROR]}: An unexpected runtime fault occurred inside the SDK engine</li>
 * </ul>
 */
public class AgentLoggingRunEventListener implements RunEventListener {

    private static final Logger log = LoggerFactory.getLogger("com.agent.agentcorelab.runner");

    private final AgentLoggingProperties properties;

    public AgentLoggingRunEventListener() {
        this(new AgentLoggingProperties());
    }

    public AgentLoggingRunEventListener(AgentLoggingProperties properties) {
        this.properties = properties != null ? properties : new AgentLoggingProperties();
    }

    @Override
    public void onEvent(RunEvent event) {
        if (event == null || !properties.isEnabled()) {
            return;
        }
        try {
            JsonNode details = ToolJson.parse(event.summary());
            String agentId = details.path("agentId").asText("unknown");
            String runIdStr = event.runId() != null ? event.runId().toString() : "unknown";

            if (properties.isMdcEnabled()) {
                MDC.put("agent.runId", runIdStr);
                MDC.put("agent.id", agentId);
            }

            try {
                handleEvent(event, details, runIdStr, agentId);
            } finally {
                if (properties.isMdcEnabled() && event.type() == RunEvent.Type.RUN_FINISHED) {
                    MDC.remove("agent.runId");
                    MDC.remove("agent.id");
                }
            }
        } catch (Exception ignored) {
            // Logging operations must never crash the host application or runner
        }
    }

    private void handleEvent(RunEvent event, JsonNode details, String runId, String agentId) {
        switch (event.type()) {
            case RUN_STARTED -> {
                String goal = truncate(details.path("goal").asText(""), properties.getMaxTextLength());
                log.info("[runId={}] [agent={}] Agent run STARTED. Goal: '{}'", runId, agentId, goal);
            }
            case MODEL_STARTED -> {
                long requestNum = details.path("request").asLong(1);
                log.debug("[runId={}] [agent={}] Requesting model inference (round #{})...", runId, agentId, requestNum);
            }
            case MODEL_FINISHED -> {
                long requestNum = details.path("request").asLong(1);
                long modelMillis = details.path("modelMillis").asLong(0);
                JsonNode usage = details.path("usage");
                if (usage.isObject()) {
                    long inputTokens = usage.path("inputTokens").asLong(0);
                    long outputTokens = usage.path("outputTokens").asLong(0);
                    log.info("[runId={}] [agent={}] Model request #{} completed in {}ms (tokens: in={}, out={})",
                            runId, agentId, requestNum, modelMillis, inputTokens, outputTokens);
                } else {
                    log.info("[runId={}] [agent={}] Model request #{} completed in {}ms",
                            runId, agentId, requestNum, modelMillis);
                }
            }
            case MODEL_RETRY -> {
                int attempt = details.path("attempt").asInt(1);
                long requestNum = details.path("request").asLong(1);
                String error = details.path("error").asText("");
                log.warn("[runId={}] [agent={}] [MODEL_TRANSIENT_RETRY] Model request #{} attempt #{} encountered transient error: {}. Retrying...",
                        runId, agentId, requestNum, attempt, truncate(error, properties.getMaxTextLength()));
            }
            case TOOL_STARTED -> {
                String tool = details.path("tool").asText("unknown");
                String callId = details.path("callId").asText("unknown");
                log.debug("[runId={}] [agent={}] Executing tool [{}] (callId={})...", runId, agentId, tool, callId);
            }
            case TOOL_FINISHED -> {
                String tool = details.path("tool").asText("unknown");
                String callId = details.path("callId").asText("unknown");
                String outcome = details.path("outcome").asText("UNKNOWN");
                String error = details.path("error").asText("");

                if ("SUCCESS".equalsIgnoreCase(outcome)) {
                    log.info("[runId={}] [agent={}] Tool [{}] executed successfully (callId={})",
                            runId, agentId, tool, callId);
                } else if (outcome.startsWith("NOT_EXECUTED") || outcome.startsWith("ACTION_SKIPPED") || outcome.startsWith("INTERRUPTED")) {
                    log.warn("[runId={}] [agent={}] Tool [{}] skipped or interrupted (callId={}, reason={})",
                            runId, agentId, tool, callId, outcome);
                } else {
                    // Clearly flag host tool failure
                    if (!error.isBlank()) {
                        log.warn("[runId={}] [agent={}] [USER_TOOL_ERROR] Tool [{}] failed with code [{}]: {}",
                                runId, agentId, tool, outcome, truncate(error, properties.getMaxTextLength()));
                    } else {
                        log.warn("[runId={}] [agent={}] [USER_TOOL_ERROR] Tool [{}] failed with code [{}]",
                                runId, agentId, tool, outcome);
                    }
                }
            }
            case APPROVAL_REQUESTED -> {
                String tool = details.path("tool").asText("unknown");
                String callId = details.path("callId").asText("unknown");
                String action = details.path("action").asText("");
                log.warn("[runId={}] [agent={}] [HITL_APPROVAL_REQUIRED] Intercepted high-risk tool [{}] (callId={}). Execution paused -> WAITING_APPROVAL. Description: {}",
                        runId, agentId, tool, callId, truncate(action, properties.getMaxTextLength()));
            }
            case APPROVAL_RESOLVED -> {
                boolean approved = details.path("approved").asBoolean(false);
                if (approved) {
                    log.info("[runId={}] [agent={}] [HITL_APPROVAL_RESOLVED] High-risk operation APPROVED by human operator. Resuming execution.",
                            runId, agentId);
                } else {
                    log.warn("[runId={}] [agent={}] [HITL_APPROVAL_RESOLVED] High-risk operation REJECTED by human operator.",
                            runId, agentId);
                }
            }
            case WAITING_INPUT -> log.info("[runId={}] [agent={}] Agent paused -> WAITING_INPUT. Awaiting clarification from user.",
                    runId, agentId);

            case INPUT_RECEIVED -> log.info("[runId={}] [agent={}] User clarification received. Resuming agent execution.",
                    runId, agentId);

            case PLAN_UPDATED -> {
                long revision = details.path("revision").asLong(1);
                String reason = details.path("reason").asText("");
                log.info("[runId={}] [agent={}] Task plan revised (revision={}, reason={})",
                        runId, agentId, revision, truncate(reason, properties.getMaxTextLength()));
            }
            case COMPACTION_TRIGGERED -> {
                long tokensAfter = details.path("tokensAfter").asLong(0);
                log.info("[runId={}] [agent={}] Context compaction triggered (tokensAfter={})", runId, agentId, tokensAfter);
            }
            case DELIVERABLE_RETRY -> {
                String feedback = truncate(details.path("feedback").asText(""), properties.getMaxTextLength());
                log.warn("[runId={}] [agent={}] Incomplete deliverable detected. Retrying with feedback: '{}'", runId, agentId, feedback);
            }
            case RUN_FINISHED -> {
                String status = details.path("status").asText("UNKNOWN");
                String reason = details.path("reason").asText("UNKNOWN");
                long activeMillis = details.path("activeMillis").asLong(0);
                long modelMillis = details.path("modelMillis").asLong(0);
                long toolMillis = details.path("toolMillis").asLong(0);
                long waitingMillis = details.path("waitingMillis").asLong(0);
                long modelRequests = details.path("modelRequests").asLong(0);
                long toolCalls = details.path("toolCalls").asLong(0);

                if ("COMPLETED".equalsIgnoreCase(status)) {
                    log.info("[runId={}] [agent={}] Agent run COMPLETED in {}ms (modelTime={}ms, toolTime={}ms, waitingTime={}ms, requests={}, tools={})",
                            runId, agentId, activeMillis, modelMillis, toolMillis, waitingMillis, modelRequests, toolCalls);
                } else if ("WAITING_INPUT".equalsIgnoreCase(status) || "WAITING_APPROVAL".equalsIgnoreCase(status)) {
                    log.info("[runId={}] [agent={}] Agent run paused in state [{}]", runId, agentId, status);
                } else if ("CANCELLED".equalsIgnoreCase(status)) {
                    log.warn("[runId={}] [agent={}] Agent run CANCELLED (activeTime={}ms)", runId, agentId, activeMillis);
                } else if ("STOPPED".equalsIgnoreCase(status)) {
                    log.warn("[runId={}] [agent={}] [BUDGET_EXHAUSTED] Agent run STOPPED due to {}. (activeTime={}ms, requests={}, tools={})",
                            runId, agentId, reason, activeMillis, modelRequests, toolCalls);
                } else {
                    String tag = classifyFailureTag(reason);
                    log.error("[runId={}] [agent={}] [{}] Agent run FAILED with reason: {}", runId, agentId, tag, reason);
                }
            }
            default -> {}
        }
    }

    private String classifyFailureTag(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SDK_INTERNAL_ERROR";
        }
        if (reason.startsWith("MODEL_")) {
            return "MODEL_GATEWAY_ERROR";
        }
        if (reason.startsWith("TOOL_")) {
            return "USER_TOOL_ERROR";
        }
        if (reason.contains("TIMEOUT") || reason.contains("LIMIT") || reason.equals("INCOMPLETE_DELIVERABLE") || reason.equals("NO_PROGRESS")) {
            return "BUDGET_EXHAUSTED";
        }
        if (reason.contains("STORAGE") || reason.contains("RESOURCE")) {
            return "SYSTEM_IO_ERROR";
        }
        return "SDK_INTERNAL_ERROR";
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        text = text.trim();
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "... [truncated]";
    }

    public AgentLoggingProperties properties() {
        return properties;
    }
}
