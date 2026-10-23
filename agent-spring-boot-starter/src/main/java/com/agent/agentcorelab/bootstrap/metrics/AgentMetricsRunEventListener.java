package com.agent.agentcorelab.bootstrap.metrics;

import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.trace.RunEventListener;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metric exporter that listens to {@link RunEvent} lifecycle occurrences and binds
 * production-ready Prometheus / Actuator metrics with strictly bounded low-cardinality tags.
 * <p>
 * Tags adhere strictly to the low-cardinality rules: only bounded {@code agent_id}, {@code status},
 * {@code reason}, {@code tool}, and {@code type} are exported as metric tags.
 * Dynamic attributes such as {@code runId}, user prompts, and conversation content are never tagged.
 */
public class AgentMetricsRunEventListener implements RunEventListener {

    private final MeterRegistry registry;
    private final AtomicInteger activeRuns = new AtomicInteger(0);

    public AgentMetricsRunEventListener(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry cannot be null");
        Gauge.builder("agent.runs.active", activeRuns, AtomicInteger::get)
                .description("Number of currently active agent runs")
                .register(registry);
    }

    @Override
    public void onEvent(RunEvent event) {
        if (event == null) {
            return;
        }
        try {
            JsonNode details = ToolJson.parse(event.summary());
            String agentId = extractSafeTag(details.path("agentId").asText("unknown"));

            switch (event.type()) {
                case RUN_STARTED -> activeRuns.incrementAndGet();

                case RUN_FINISHED -> {
                    activeRuns.updateAndGet(c -> Math.max(0, c - 1));
                    String status = extractSafeTag(details.path("status").asText("UNKNOWN"));
                    String reason = extractSafeTag(details.path("reason").asText("UNKNOWN"));

                    Counter.builder("agent.runs.total")
                            .tags("agent_id", agentId, "status", status, "reason", reason)
                            .description("Total number of completed or terminated agent runs")
                            .register(registry)
                            .increment();

                    long activeMillis = details.path("activeMillis").asLong(0);
                    Timer.builder("agent.run.active.duration")
                            .tags("agent_id", agentId, "status", status)
                            .description("Active execution duration of agent runs")
                            .register(registry)
                            .record(Duration.ofMillis(activeMillis));

                    long modelMillis = details.path("modelMillis").asLong(0);
                    Timer.builder("agent.run.model.duration")
                            .tags("agent_id", agentId)
                            .description("Duration spent waiting for model inference in agent runs")
                            .register(registry)
                            .record(Duration.ofMillis(modelMillis));

                    long toolMillis = details.path("toolMillis").asLong(0);
                    Timer.builder("agent.run.tool.duration")
                            .tags("agent_id", agentId)
                            .description("Duration spent executing tools in agent runs")
                            .register(registry)
                            .record(Duration.ofMillis(toolMillis));

                    long waitingMillis = details.path("waitingMillis").asLong(0);
                    Timer.builder("agent.run.waiting.duration")
                            .tags("agent_id", agentId)
                            .description("Duration spent waiting for human input or approval in agent runs")
                            .register(registry)
                            .record(Duration.ofMillis(waitingMillis));

                    JsonNode usage = details.path("usage");
                    if (usage.isObject()) {
                        long inputTokens = usage.path("inputTokens").asLong(0);
                        long outputTokens = usage.path("outputTokens").asLong(0);
                        if (inputTokens > 0) {
                            Counter.builder("agent.run.tokens.total")
                                    .tags("agent_id", agentId, "type", "input")
                                    .description("Cumulative count of tokens consumed by agent runs")
                                    .register(registry)
                                    .increment(inputTokens);
                        }
                        if (outputTokens > 0) {
                            Counter.builder("agent.run.tokens.total")
                                    .tags("agent_id", agentId, "type", "output")
                                    .description("Cumulative count of tokens consumed by agent runs")
                                    .register(registry)
                                    .increment(outputTokens);
                        }
                    }
                }

                case MODEL_FINISHED -> Counter.builder("agent.run.model.requests")
                        .tags("agent_id", agentId)
                        .description("Total model requests issued by agent runs")
                        .register(registry)
                        .increment();

                case MODEL_RETRY -> Counter.builder("agent.run.model.retries")
                        .tags("agent_id", agentId)
                        .description("Total model transient retries executed by agent runs")
                        .register(registry)
                        .increment();

                case TOOL_FINISHED -> {
                    String tool = extractSafeTag(details.path("tool").asText("unknown"));
                    Counter.builder("agent.run.tool.calls")
                            .tags("agent_id", agentId, "tool", tool)
                            .description("Total tool calls executed by agent runs")
                            .register(registry)
                            .increment();
                }

                case APPROVAL_REQUESTED -> Counter.builder("agent.run.approvals.requested")
                        .tags("agent_id", agentId)
                        .description("Total approval requests triggered by high risk operations")
                        .register(registry)
                        .increment();

                case APPROVAL_RESOLVED -> {
                    boolean approved = details.path("approved").asBoolean(false);
                    Counter.builder("agent.run.approvals.resolved")
                            .tags("agent_id", agentId, "approved", String.valueOf(approved))
                            .description("Total approvals resolved by human operator")
                            .register(registry)
                            .increment();
                }

                case WAITING_INPUT -> Counter.builder("agent.run.clarifications.requested")
                        .tags("agent_id", agentId)
                        .description("Total clarification requests waiting for human input")
                        .register(registry)
                        .increment();

                case COMPACTION_TRIGGERED -> Counter.builder("agent.run.context.compactions")
                        .tags("agent_id", agentId)
                        .description("Total context compactions triggered by agent runs")
                        .register(registry)
                        .increment();

                case DELIVERABLE_RETRY -> Counter.builder("agent.run.deliverable.retries")
                        .tags("agent_id", agentId)
                        .description("Total deliverable completion repair retries triggered by agent runs")
                        .register(registry)
                        .increment();

                case RUN_TIMEOUT -> {
                    String timeoutType = extractSafeTag(details.path("timeoutType").asText("TIMEOUT"));
                    Counter.builder("agent.run.timeouts")
                            .tags("agent_id", agentId, "type", timeoutType)
                            .description("Total number of run timeouts (e.g. waiting timeout)")
                            .register(registry)
                            .increment();
                }

                default -> {
                    // Other intermediate events (TOOL_STARTED, MODEL_STARTED, BRIEF_UPDATED, etc.) do not emit metrics
                }
            }
        } catch (Exception ignored) {
            // Metrics capture must never crash the host application
        }
    }

    public int activeRuns() {
        return activeRuns.get();
    }

    public MeterRegistry registry() {
        return registry;
    }

    private static String extractSafeTag(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.trim();
    }
}
