package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.plan.TaskBrief;
import com.agent.agentcorelab.agent.plan.TaskPlan;
import com.agent.agentcorelab.agent.plan.TaskStep;
import com.agent.agentcorelab.agent.policy.ActiveTaskConflictException;
import com.agent.agentcorelab.agent.policy.ActiveTaskPolicy;
import com.agent.agentcorelab.agent.policy.ExecutionPolicy;
import com.agent.agentcorelab.agent.run.ApprovalRequest;
import com.agent.agentcorelab.agent.run.PendingQuestion;
import com.agent.agentcorelab.agent.run.RunLimits;
import com.agent.agentcorelab.agent.run.RunMetrics;
import com.agent.agentcorelab.agent.run.RunRequest;
import com.agent.agentcorelab.agent.run.RunResult;
import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;
import com.agent.agentcorelab.agent.run.RunStreamChunk;
import com.agent.agentcorelab.model.ModelGateway;
import com.agent.agentcorelab.model.ModelResponse;
import com.agent.agentcorelab.model.ModelSession;
import com.agent.agentcorelab.model.ModelStreamChunk;
import com.agent.agentcorelab.tool.ActiveTimeExceededException;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolCall;
import com.agent.agentcorelab.tool.ToolCatalog;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolExecutor;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.agent.agentcorelab.tool.builtin.CheckDeliverableTool;
import com.agent.agentcorelab.tool.builtin.ClarificationTool;
import com.agent.agentcorelab.tool.builtin.ReadWorkItemTool;
import com.agent.agentcorelab.tool.builtin.SaveWorkNoteTool;
import com.agent.agentcorelab.tool.builtin.UpdatePlanTool;
import com.agent.agentcorelab.trace.RunEvent;
import com.agent.agentcorelab.trace.RunEventSink;
import com.agent.agentcorelab.memory.ChatMessage;
import com.agent.agentcorelab.memory.ConversationMemory;
import com.agent.agentcorelab.memory.InMemoryConversationMemory;
import com.agent.agentcorelab.memory.UserProfileMemory;
import com.agent.agentcorelab.memory.InMemoryUserProfileMemory;
import com.agent.agentcorelab.workspace.Artifact;
import com.agent.agentcorelab.workspace.WorkspaceException;
import com.agent.agentcorelab.workspace.WorkspaceSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class DefaultAgentRunner implements AgentRunner, AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(DefaultAgentRunner.class.getName());
    public static final int DEFAULT_MAX_ACTIVE_RUNS = 1000;
    private static final String RUNTIME_RULES = ExecutionPolicy.DEFAULT_RUNTIME_RULES;

    private static void safeEmit(Consumer<RunStreamChunk> listener, RunStreamChunk chunk) {
        if (listener == null || chunk == null) {
            return;
        }
        try {
            listener.accept(chunk);
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.WARNING, "Exception thrown by RunStreamChunk listener: {0}", t.getMessage(), t);
        }
    }

    private final ModelGateway model;
    private final ToolCatalog tools;
    private final RunEventSink events;
    private final ConversationMemory memory;
    private final UserProfileMemory userProfiles;
    private final LongSupplier nanoTime;
    private final int maxActiveRuns;
    private final ReentrantLock owner = new ReentrantLock();
    private final Map<UUID, State> runs = new ConcurrentHashMap<>();
    private volatile State current;
    private volatile boolean closed;

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events) {
        this(model, tools, events, new InMemoryConversationMemory(), new InMemoryUserProfileMemory(), System::nanoTime, DEFAULT_MAX_ACTIVE_RUNS);
    }

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory) {
        this(model, tools, events, memory, new InMemoryUserProfileMemory(), System::nanoTime, DEFAULT_MAX_ACTIVE_RUNS);
    }

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory, UserProfileMemory userProfiles) {
        this(model, tools, events, memory, userProfiles, System::nanoTime, DEFAULT_MAX_ACTIVE_RUNS);
    }

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, int maxActiveRuns) {
        this(model, tools, events, new InMemoryConversationMemory(), new InMemoryUserProfileMemory(), System::nanoTime, maxActiveRuns);
    }

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory, int maxActiveRuns) {
        this(model, tools, events, memory, new InMemoryUserProfileMemory(), System::nanoTime, maxActiveRuns);
    }

    public DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory, UserProfileMemory userProfiles, int maxActiveRuns) {
        this(model, tools, events, memory, userProfiles, System::nanoTime, maxActiveRuns);
    }

    DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, LongSupplier nanoTime) {
        this(model, tools, events, new InMemoryConversationMemory(), new InMemoryUserProfileMemory(), nanoTime, DEFAULT_MAX_ACTIVE_RUNS);
    }

    DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, LongSupplier nanoTime, int maxActiveRuns) {
        this(model, tools, events, new InMemoryConversationMemory(), new InMemoryUserProfileMemory(), nanoTime, maxActiveRuns);
    }

    DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory, LongSupplier nanoTime, int maxActiveRuns) {
        this(model, tools, events, memory, new InMemoryUserProfileMemory(), nanoTime, maxActiveRuns);
    }

    DefaultAgentRunner(ModelGateway model, ToolCatalog tools, RunEventSink events, ConversationMemory memory, UserProfileMemory userProfiles, LongSupplier nanoTime, int maxActiveRuns) {
        this.model = model;
        this.tools = tools;
        this.events = events;
        this.memory = memory != null ? memory : new InMemoryConversationMemory();
        this.userProfiles = userProfiles != null ? userProfiles : new InMemoryUserProfileMemory();
        this.nanoTime = nanoTime;
        this.maxActiveRuns = maxActiveRuns > 0 ? maxActiveRuns : DEFAULT_MAX_ACTIVE_RUNS;
    }

    @Override
    public RunResult run(AgentSpec agent, RunRequest request) {
        return run(agent, request, agent.policy());
    }

    @Override
    public RunResult run(AgentSpec agent, RunRequest request, ExecutionPolicy policy) {
        return run(agent, request, policy, null);
    }

    @Override
    public RunResult run(AgentSpec agent, RunRequest request, ExecutionPolicy policy, Consumer<RunStreamChunk> listener) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(request, "request");
        ExecutionPolicy effectivePolicy = policy != null ? policy : agent.policy();
        acquire();
        try {
            if (closed) {
                throw new IllegalStateException("Runner is closed");
            }
            if (current != null && (current.active || !terminal(current.status))) {
                ActiveTaskPolicy taskPolicy = effectivePolicy.activeTaskPolicy();
                switch (taskPolicy) {
                    case RESUME_IF_WAITING -> {
                        if (current.status == RunStatus.WAITING_INPUT) {
                            return doResume(current, request.goal(), listener);
                        }
                        throw new ActiveTaskConflictException(current.id, current.status, current.pending);
                    }
                    case PREEMPT_IF_WAITING -> {
                        if (current.status.isWaiting()) {
                            cancelWaiting(current, "PREEMPTED", "Task cancelled by preemption of a new task");
                        } else {
                            throw new ActiveTaskConflictException(current.id, current.status, current.pending);
                        }
                    }
                    case PREEMPT_AND_ABORT -> {
                        if (current.status.isWaiting()) {
                            cancelWaiting(current, "PREEMPTED", "Task cancelled by preemption of a new task");
                        } else {
                            current.cancelled.set(true);
                            throw new ActiveTaskConflictException(current.id, current.status, current.pending);
                        }
                    }
                    case REJECT -> {
                        throw new ActiveTaskConflictException(current.id, current.status, current.pending);
                    }
                }
            }
            ensureCapacity();
            ToolCatalog selected = resolveCatalog(agent);
            ToolExecutor executor = new ToolExecutor(selected, agent.limits().maxToolResultBytes());
            State state = new State(agent, request, executor, selected, effectivePolicy);
            runs.put(state.id, state);
            current = state;
            String rules = effectivePolicy.runtimeRules().orElse(ExecutionPolicy.DEFAULT_RUNTIME_RULES);
            return drive(state, () -> {
                state.resources = new WorkspaceSession(request.workspace(), state.id);
                state.resources.setGoalVersion(state.taskBrief.goalVersion());
                state.traceReady = true;
                String userPrompt = request.initialBrief().isPresent()
                        ? state.taskBrief.toPromptBlock() + "\n\nUser Request: " + request.goal()
                        : request.goal();
                List<ChatMessage> history = request.conversationId()
                        .map(memory::getHistory)
                        .orElse(List.of());
                String userProfileContext = request.userId()
                        .map(userProfiles::getProfile)
                        .filter(p -> !p.isEmpty())
                        .map(p -> {
                            StringBuilder sb = new StringBuilder("\n\n【用户长期画像与偏好设置】\n");
                            p.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                            return sb.toString();
                        })
                        .orElse("");
                state.session = model.openSession(rules + "\n" + agent.instructions() + userProfileContext, history, userPrompt,
                        selected.definitions());
                request.conversationId().ifPresent(cid ->
                        memory.append(cid, ChatMessage.user(request.goal())));
                event(state, RunEvent.Type.RUN_STARTED, Map.of("goal", request.goal(), "agentId", agent.id()));
            }, listener);
        } finally {
            unlockOwner();
        }
    }

    private void ensureCapacity() {
        if (runs.size() < maxActiveRuns) {
            return;
        }
        pruneExpiredRuns();
        if (runs.size() < maxActiveRuns) {
            return;
        }
        List<State> terminalRuns = runs.values().stream()
                .filter(s -> s.status.isTerminal())
                .sorted(Comparator.comparing(s -> s.lastAccessedAt))
                .toList();
        for (State s : terminalRuns) {
            runs.remove(s.id);
            if (runs.size() < maxActiveRuns) {
                return;
            }
        }
        List<State> waitingRuns = runs.values().stream()
                .filter(s -> s.status.isWaiting())
                .sorted(Comparator.comparing(s -> s.lastAccessedAt))
                .toList();
        for (State s : waitingRuns) {
            synchronized (s) {
                cancelWaiting(s, "EVICTED", "Evicted to enforce capacity limit of " + maxActiveRuns);
            }
            runs.remove(s.id);
            if (runs.size() < maxActiveRuns) {
                return;
            }
        }
        if (runs.size() >= maxActiveRuns) {
            throw new IllegalStateException("Max active runs capacity reached (" + maxActiveRuns + ")");
        }
    }

    private boolean checkWaitingTimeout(State state) {
        if (state.status.isWaiting() && state.waitingSince != null) {
            Duration elapsed = Duration.between(state.waitingSince, Instant.now());
            if (elapsed.compareTo(state.spec.limits().waitingTimeout()) > 0) {
                event(state, RunEvent.Type.RUN_TIMEOUT, Map.of(
                        "timeoutType", "WAITING_TIMEOUT",
                        "status", state.status.name(),
                        "waitingSeconds", elapsed.toSeconds(),
                        "timeoutSeconds", state.spec.limits().waitingTimeout().toSeconds()
                ));
                finish(state, RunStatus.STOPPED, "WAITING_TIMEOUT",
                        "Run timed out after " + elapsed.toSeconds() + "s while waiting for " + state.status.name());
                release(state);
                emitFinished(state);
                publish(state);
                return true;
            }
        }
        return false;
    }

    @Override
    public int pruneExpiredRuns() {
        int count = 0;
        for (State state : runs.values()) {
            if (state.status.isWaiting()) {
                synchronized (state) {
                    if (checkWaitingTimeout(state)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    @Override
    public int cleanupTerminatedRuns() {
        int count = 0;
        for (State state : runs.values()) {
            if (state.status.isTerminal()) {
                runs.remove(state.id);
                count++;
            }
        }
        return count;
    }

    @Override
    public int activeRunCount() {
        return runs.size();
    }

    @Override
    public void closeRun(UUID runId) {
        State state = runs.remove(runId);
        if (state != null) {
            if (!state.status.isTerminal()) {
                cancelWaiting(state, "CLOSED", "Run closed explicitly");
            }
            if (state.resources != null) {
                state.resources.close();
            }
        }
    }

    @Override
    public ConversationMemory memory() {
        return memory;
    }

    @Override
    public UserProfileMemory userProfiles() {
        return userProfiles;
    }

    @Override
    public RunResult resume(UUID runId, String answer) {
        return resume(runId, answer, null);
    }

    @Override
    public RunResult resume(UUID runId, String answer, Consumer<RunStreamChunk> listener) {
        Objects.requireNonNull(answer, "answer");
        acquire();
        try {
            State state = require(runId);
            state.lastAccessedAt = Instant.now();
            if (checkWaitingTimeout(state)) {
                return result(state);
            }
            if (closed || state != current || state.status != RunStatus.WAITING_INPUT) {
                throw new IllegalStateException("Only the pending question of the active task can be answered");
            }
            return doResume(state, answer, listener);
        } finally {
            unlockOwner();
        }
    }

    private void enterWaiting(State state, RunStatus status, String message) {
        state.status = status;
        state.message = message;
        state.waitingSince = Instant.now();
        state.lastAccessedAt = Instant.now();
        state.waitingStartedAtNanos = nanoTime.getAsLong();
        state.waitingTimingActive = true;
    }

    private void exitWaiting(State state) {
        if (state.waitingTimingActive) {
            state.waitingNanos += Math.max(0L, nanoTime.getAsLong() - state.waitingStartedAtNanos);
            state.waitingTimingActive = false;
        }
        state.waitingSince = null;
    }

    private long waitingElapsed(State state) {
        return state.waitingTimingActive
                ? state.waitingNanos + Math.max(0L, nanoTime.getAsLong() - state.waitingStartedAtNanos)
                : state.waitingNanos;
    }

    private RunResult doResume(State state, String answer, Consumer<RunStreamChunk> listener) {
        return drive(state, () -> {
            ToolCall call = state.pendingCall;
            exitWaiting(state);
            state.status = RunStatus.RUNNING;
            state.wasResumedFromClarification = true;
            if (call != null) {
                accept(state, call, new ToolOutcome.Success(ToolJson.write(Map.of(
                        "ok", true, "data", Map.of("answer", answer, "source", "current_user")))), listener);
            } else {
                state.session.appendUserMessage(answer);
            }
            state.request.conversationId().ifPresent(cid ->
                    memory.append(cid, ChatMessage.user(answer)));
            state.pending = null;
            state.pendingCall = null;
            state.lastFingerprint = null;
            state.repeated = 0;
            state.errors = 0;
            event(state, RunEvent.Type.INPUT_RECEIVED, Map.of("callId", call != null ? call.callId() : "sub_dialogue"));
        }, listener);
    }

    @Override
    public RunResult approve(UUID runId, String approvalId, Consumer<RunStreamChunk> listener) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(approvalId, "approvalId");
        acquire();
        try {
            State state = require(runId);
            state.lastAccessedAt = Instant.now();
            if (checkWaitingTimeout(state)) {
                return result(state);
            }
            if (closed || state != current || state.status != RunStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("Only the pending approval of the active task can be approved");
            }
            if (state.pendingApproval == null || !approvalId.equals(state.pendingApproval.approvalId())) {
                throw new IllegalArgumentException("Approval ID mismatch or expired: " + approvalId);
            }
            return doApprove(state, listener);
        } finally {
            unlockOwner();
        }
    }

    private RunResult doApprove(State state, Consumer<RunStreamChunk> listener) {
        return drive(state, () -> {
            ToolCall call = state.pendingHighRiskCall;
            List<ToolCall> calls = state.pendingHighRiskCalls;
            int index = state.pendingHighRiskIndex;
            String approvalId = state.pendingApproval != null ? state.pendingApproval.approvalId() : "";

            exitWaiting(state);
            state.status = RunStatus.RUNNING;
            state.pendingApproval = null;
            state.pendingHighRiskCall = null;
            state.pendingHighRiskCalls = null;

            event(state, RunEvent.Type.APPROVAL_RESOLVED, Map.of(
                    "approvalId", approvalId,
                    "callId", call.callId(),
                    "tool", call.name(),
                    "decision", "APPROVED"
            ));

            event(state, RunEvent.Type.TOOL_STARTED, Map.of("callId", call.callId(), "tool", call.name(),
                    "argumentsSha256", digest(call.argumentsJson())));
            safeEmit(listener, RunStreamChunk.toolStarted(call.callId(), call.name(), Map.of("callId", call.callId())));
            ToolContext context = new ToolContext(state.id, state.request.workspace(),
                    Instant.now().plus(remaining(state)), state::isCancellationRequested,
                    artifact -> state.artifacts.add(artifact), state.resources);
            ToolOutcome outcome;
            long toolStart = nanoTime.getAsLong();
            try {
                state.currentToolThread.set(Thread.currentThread());
                try {
                    outcome = state.executor.execute(call, context);
                } finally {
                    state.currentToolThread.set(null);
                    state.toolNanos += Math.max(0L, nanoTime.getAsLong() - toolStart);
                }
            } catch (CancellationException | ActiveTimeExceededException ex) {
                boolean cancelled = ex instanceof CancellationException;
                event(state, RunEvent.Type.TOOL_FINISHED, Map.of("callId", call.callId(), "tool", call.name(),
                        "outcome", cancelled ? "INTERRUPTED_CANCELLED" : "INTERRUPTED_ACTIVE_TIME_LIMIT"));
                finish(state, cancelled ? RunStatus.CANCELLED : RunStatus.STOPPED,
                        cancelled ? "CANCELLED" : "ACTIVE_TIME_LIMIT", "Tool interrupted by a runtime control condition");
                return;
            }
            accept(state, call, outcome, listener);
            if (controlledStop(state)) {
                return;
            }
            if (calls != null && index + 1 < calls.size()) {
                executeToolCalls(state, calls.subList(index + 1, calls.size()), listener);
            }
        }, listener);
    }

    @Override
    public RunResult reject(UUID runId, String approvalId, String reason, Consumer<RunStreamChunk> listener) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(approvalId, "approvalId");
        String rejectionReason = reason != null && !reason.isBlank() ? reason : "Action rejected by user";
        acquire();
        try {
            State state = require(runId);
            state.lastAccessedAt = Instant.now();
            if (checkWaitingTimeout(state)) {
                return result(state);
            }
            if (closed || state != current || state.status != RunStatus.WAITING_APPROVAL) {
                throw new IllegalStateException("Only the pending approval of the active task can be rejected");
            }
            if (state.pendingApproval == null || !approvalId.equals(state.pendingApproval.approvalId())) {
                throw new IllegalArgumentException("Approval ID mismatch or expired: " + approvalId);
            }
            return doReject(state, rejectionReason, listener);
        } finally {
            unlockOwner();
        }
    }

    private RunResult doReject(State state, String rejectionReason, Consumer<RunStreamChunk> listener) {
        return drive(state, () -> {
            ToolCall call = state.pendingHighRiskCall;
            List<ToolCall> calls = state.pendingHighRiskCalls;
            int index = state.pendingHighRiskIndex;
            String approvalId = state.pendingApproval != null ? state.pendingApproval.approvalId() : "";

            exitWaiting(state);
            state.status = RunStatus.RUNNING;
            state.pendingApproval = null;
            state.pendingHighRiskCall = null;
            state.pendingHighRiskCalls = null;

            event(state, RunEvent.Type.APPROVAL_RESOLVED, Map.of(
                    "approvalId", approvalId,
                    "callId", call.callId(),
                    "tool", call.name(),
                    "decision", "REJECTED",
                    "reason", rejectionReason
            ));

            ToolOutcome outcome = new ToolOutcome.Failure("ACTION_REJECTED",
                    "User rejected action '" + call.name() + "': " + rejectionReason, false);
            accept(state, call, outcome, listener);
            if (calls != null && index + 1 < calls.size()) {
                for (int i = index + 1; i < calls.size(); i++) {
                    ToolCall skippedCall = calls.get(i);
                    ToolOutcome skippedOutcome = new ToolOutcome.Failure("ACTION_SKIPPED",
                            "Tool call skipped because preceding action '" + call.name() + "' in batch was rejected: " + rejectionReason,
                            false);
                    state.observations.add(new RunSnapshot.Observation(skippedCall, skippedOutcome));
                    state.session.appendToolResult(skippedCall.callId(), skippedOutcome);
                    event(state, RunEvent.Type.TOOL_FINISHED, Map.of(
                            "callId", skippedCall.callId(),
                            "tool", skippedCall.name(),
                            "outcome", "ACTION_SKIPPED"
                    ));
                    safeEmit(listener, RunStreamChunk.toolFinished(skippedCall.callId(), skippedCall.name(), "ACTION_SKIPPED"));
                }
            }
        }, listener);
    }

    @Override
    public RunResult reviseGoal(UUID runId, TaskBrief revisedBrief, Consumer<RunStreamChunk> listener) {
        Objects.requireNonNull(revisedBrief, "revisedBrief");
        acquire();
        try {
            State state = require(runId);
            state.lastAccessedAt = Instant.now();
            if (checkWaitingTimeout(state)) {
                return result(state);
            }
            if (closed || state != current || terminal(state.status)) {
                throw new IllegalStateException("Only active or waiting tasks can have their goal revised");
            }
            int currentVersion = state.taskBrief != null ? state.taskBrief.goalVersion() : 0;
            if (revisedBrief.goalVersion() <= currentVersion) {
                throw new IllegalArgumentException("Revised goalVersion must be strictly greater than current version ("
                        + currentVersion + "), got: " + revisedBrief.goalVersion());
            }
            return doReviseGoal(state, revisedBrief, listener);
        } finally {
            unlockOwner();
        }
    }

    private RunResult doReviseGoal(State state, TaskBrief revisedBrief, Consumer<RunStreamChunk> listener) {
        return drive(state, () -> {
            exitWaiting(state);
            state.status = RunStatus.RUNNING;
            if (state.pendingCall != null) {
                ToolCall call = state.pendingCall;
                state.pending = null;
                state.pendingCall = null;
                accept(state, call, new ToolOutcome.Failure("QUESTION_SUPERSEDED",
                        "Question was superseded by user goal revision", false), listener);
            } else if (state.pending != null) {
                state.pending = null;
            }
            if (state.pendingHighRiskCall != null) {
                ToolCall call = state.pendingHighRiskCall;
                List<ToolCall> calls = state.pendingHighRiskCalls;
                int index = state.pendingHighRiskIndex;
                String approvalId = state.pendingApproval != null ? state.pendingApproval.approvalId() : "";
                state.pendingApproval = null;
                state.pendingHighRiskCall = null;
                state.pendingHighRiskCalls = null;
                event(state, RunEvent.Type.APPROVAL_RESOLVED, Map.of(
                        "approvalId", approvalId,
                        "callId", call.callId(),
                        "tool", call.name(),
                        "decision", "SUPERSEDED"
                ));
                accept(state, call, new ToolOutcome.Failure("APPROVAL_SUPERSEDED",
                        "High-risk action was superseded by user goal revision", false), listener);
                if (calls != null && index + 1 < calls.size()) {
                    for (int i = index + 1; i < calls.size(); i++) {
                        ToolCall skippedCall = calls.get(i);
                        ToolOutcome skippedOutcome = new ToolOutcome.Failure("ACTION_SKIPPED",
                                "Tool call skipped because preceding action '" + call.name() + "' in batch was superseded by goal revision",
                                false);
                        state.observations.add(new RunSnapshot.Observation(skippedCall, skippedOutcome));
                        state.session.appendToolResult(skippedCall.callId(), skippedOutcome);
                        event(state, RunEvent.Type.TOOL_FINISHED, Map.of(
                                "callId", skippedCall.callId(),
                                "tool", skippedCall.name(),
                                "outcome", "ACTION_SKIPPED"
                        ));
                        safeEmit(listener, RunStreamChunk.toolFinished(skippedCall.callId(), skippedCall.name(), "ACTION_SKIPPED"));
                    }
                }
            } else if (state.pendingApproval != null) {
                state.pendingApproval = null;
            }
            state.taskBrief = revisedBrief;
            state.currentGoal = revisedBrief.objective();
            if (state.resources != null) {
                state.resources.setGoalVersion(revisedBrief.goalVersion());
            }
            state.taskPlan = TaskPlan.forRevisedGoal(revisedBrief.goalVersion());
            event(state, RunEvent.Type.BRIEF_UPDATED, Map.of(
                    "goalVersion", revisedBrief.goalVersion(),
                    "objective", revisedBrief.objective()
            ));
            safeEmit(listener, RunStreamChunk.briefUpdated(revisedBrief.goalVersion(),
                    revisedBrief.objective(), revisedBrief.deliverables()));
            state.session.appendUserMessage("Goal revised to version " + revisedBrief.goalVersion() + ":\n"
                    + revisedBrief.toPromptBlock()
                    + "\nPlease update your plan with updatePlan and adapt your actions to meet the revised goal.");
        }, listener);
    }

    private ToolCatalog resolveCatalog(AgentSpec agent) {
        Map<String, Tool> available = new LinkedHashMap<>();
        available.put(ClarificationTool.NAME, new ClarificationTool());
        available.put(ReadWorkItemTool.NAME, new ReadWorkItemTool());
        available.put(UpdatePlanTool.NAME, new UpdatePlanTool());
        available.put(SaveWorkNoteTool.NAME, new SaveWorkNoteTool());
        available.put(CheckDeliverableTool.NAME, new CheckDeliverableTool());
        for (ToolDefinition def : tools.definitions()) {
            available.put(def.name(), tools.require(def.name()));
        }
        return new ToolCatalog(available.values()).select(agent.toolNames());
    }

    @Override
    public void cancel(UUID runId) {
        State state = require(runId);
        boolean wasWaiting = false;
        synchronized (state) {
            if (terminal(state.status)) {
                return;
            }
            state.cancelled.set(true);
            if (state.status.isWaiting()) {
                wasWaiting = true;
                cancelWaiting(state);
            } else {
                finish(state, RunStatus.CANCELLED, "CANCELLED", "Task cancelled; existing artifacts were retained");
                publish(state);
            }
        }
        state.abortActiveOperations();
        if (wasWaiting) {
            release(state);
            emitFinished(state);
            publish(state);
        }
    }

    @Override
    public RunSnapshot snapshot(UUID runId) {
        return require(runId).snapshot;
    }

    @Override
    public Optional<RunSnapshot> currentSnapshot() {
        State state = current;
        return state == null ? Optional.empty() : Optional.of(state.snapshot);
    }

    private RunResult drive(State state, StartAction start, Consumer<RunStreamChunk> listener) {
        state.runnerThread.set(Thread.currentThread());
        state.startedAt = nanoTime.getAsLong();
        state.active = true;
        try {
            safeEmit(listener, RunStreamChunk.statusChanged(RunStatus.RUNNING));
            if (!controlledStop(state)) {
                start.run();
                publish(state);
                while (state.status == RunStatus.RUNNING && !controlledStop(state)) {
                    RunLimits limits = state.spec.limits();
                    if (state.modelRequests >= limits.maxModelRequests()) {
                        finish(state, RunStatus.STOPPED, "MAX_MODEL_REQUESTS", "Model request budget exhausted");
                        break;
                    }
                    long contextBudget = (long) limits.maxContextTokens() - limits.outputTokenReserve();
                    if (state.session.estimatedInputTokens() > (long) (contextBudget * 0.75)) {
                        if (state.session.compactContext((long) (contextBudget * 0.5))) {
                            event(state, RunEvent.Type.COMPACTION_TRIGGERED, Map.of("compacted", true, "tokensAfter", state.session.estimatedInputTokens()));
                        }
                    }
                    if (state.session.estimatedInputTokens() > contextBudget) {
                        finish(state, RunStatus.STOPPED, "CONTEXT_LIMIT", "Context budget exhausted; no history was silently removed");
                        break;
                    }
                    if (controlledStop(state)) {
                        break;
                    }
                    ModelResponse response = null;
                    int retries = 0;
                    int maxRetries = limits.maxModelRetries();
                    while (true) {
                        state.modelRequests++;
                        event(state, RunEvent.Type.MODEL_STARTED, Map.of("request", state.modelRequests));
                        if (controlledStop(state)) {
                            break;
                        }
                        AtomicBoolean outputEmitted = new AtomicBoolean(false);
                        long modelStart = nanoTime.getAsLong();
                        try {
                            try {
                                Duration callTimeout = min(limits.modelTimeout(), remaining(state));
                                response = listener == null
                                        ? state.session.nextResponse(callTimeout)
                                        : state.session.nextResponse(callTimeout, chunk -> {
                                            if (chunk != null && chunk.text() != null && !chunk.text().isEmpty()) {
                                                outputEmitted.set(true);
                                                if (chunk.type() == ModelStreamChunk.Type.THINKING) {
                                                    safeEmit(listener, RunStreamChunk.thinking(chunk.text()));
                                                } else {
                                                    safeEmit(listener, RunStreamChunk.text(chunk.text()));
                                                }
                                            }
                                        });
                            } finally {
                                state.modelNanos += Math.max(0L, nanoTime.getAsLong() - modelStart);
                            }
                            break;
                        } catch (RuntimeException ex) {
                            state.usageKnown = false;
                            if (controlledStop(state)) {
                                break;
                            }
                            boolean canRetry = !outputEmitted.get()
                                    && retries < maxRetries
                                    && isTransientModelError(state, ex)
                                    && state.modelRequests < limits.maxModelRequests();
                            if (canRetry) {
                                retries++;
                                if (state.session != null) {
                                    state.session.resetTransientFailure();
                                }
                                event(state, RunEvent.Type.MODEL_RETRY, Map.of(
                                        "request", state.modelRequests,
                                        "attempt", retries,
                                        "error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()
                                ));
                                continue;
                            }
                            finish(state, RunStatus.FAILED, isTimeout(ex) ? "MODEL_TIMEOUT" : "MODEL_FAILED",
                                    retries > 0
                                            ? "Model request failed after " + retries + " retries: " + ex.getMessage()
                                            : "Model request failed; no automatic retry was made");
                            break;
                        }
                    }
                    if (state.status != RunStatus.RUNNING || controlledStop(state)) {
                        break;
                    }
                    recordUsage(state, response);
                    if (controlledStop(state)) {
                        break;
                    }
                    String protocolError = protocolError(state, response);
                    if (protocolError != null) {
                        finish(state, RunStatus.FAILED, protocolError, "Model violated the tool calling protocol");
                        break;
                    }
                    event(state, RunEvent.Type.MODEL_FINISHED, Map.of("request", state.modelRequests,
                            "finishReason", response.finishReason().name(), "outputTokens",
                            response.usage().map(ModelResponse.TokenUsage::outputTokens).orElse(-1L)));
                    if (controlledStop(state)) {
                        break;
                    }
                    if (!response.toolCalls().isEmpty()) {
                        executeToolCalls(state, response.toolCalls(), listener);
                    } else {
                        complete(state, response.text(), listener);
                    }
                }
            }
        } catch (WorkspaceException ex) {
            finish(state, ex.terminal() ? RunStatus.FAILED : RunStatus.STOPPED, ex.code(), ex.getMessage());
        } catch (IOException ex) {
            finish(state, RunStatus.FAILED, "STORAGE_FAILED", "A workspace storage error occurred");
        } catch (UncheckedIOException ex) {
            finish(state, RunStatus.FAILED, "STORAGE_FAILED", "A workspace storage error occurred");
        } catch (RuntimeException ex) {
            finish(state, RunStatus.FAILED, "INTERNAL_ERROR", "Unexpected runtime error: " + ex.getMessage());
        } finally {
            state.runnerThread.set(null);
            Thread.interrupted();
            state.active = false;
            state.accumulatedNanos += nanoTime.getAsLong() - state.startedAt;
            if (terminal(state.status)) {
                release(state);
                emitFinished(state);
            }
            publish(state);
            safeEmit(listener, RunStreamChunk.statusChanged(state.status));
        }
        return result(state);
    }

    private void executeToolCalls(State state, List<ToolCall> calls, Consumer<RunStreamChunk> listener) throws IOException {
        if (state.toolCalls + calls.size() > state.spec.limits().maxToolCalls()) {
            finish(state, RunStatus.STOPPED, "MAX_TOOL_CALLS", "Tool call budget exhausted");
            skipped(state, calls, 0);
            return;
        }
        state.wasResumedFromClarification = false;
        state.clarificationSubDialogueTurns = 0;
        boolean mixed = calls.size() > 1 && calls.stream().anyMatch(c -> isControlTool(c.name()));
        for (int index = 0; index < calls.size(); index++) {
            state.toolCalls++;
            ToolCall call = calls.get(index);
            if (controlledStop(state)) {
                skipped(state, calls, index);
                return;
            }
            if (!mixed) {
                String fingerprint = ToolExecutor.fingerprint(call);
                state.repeated = fingerprint.equals(state.lastFingerprint) ? state.repeated + 1 : 1;
                state.lastFingerprint = fingerprint;
                if (state.repeated >= state.spec.limits().maxRepeatedToolCalls()) {
                    finish(state, RunStatus.STOPPED, "NO_PROGRESS", "Repeated identical tool requests made no progress");
                    skipped(state, calls, index);
                    return;
                }
            }
            ToolOutcome outcome = mixed
                    ? new ToolOutcome.Failure("MIXED_CONTROL_BATCH", "Control tools (updatePlan, requestClarification) must be the only call in a response", false)
                    : state.executor.validate(call);
            if (outcome == null && ClarificationTool.NAME.equals(call.name())) {
                var arguments = ToolJson.parse(call.argumentsJson());
                String question = arguments.path("question").asText();
                if (question.isBlank()) {
                    outcome = new ToolOutcome.Failure("INVALID_ARGUMENT", "Question must not be blank", false);
                } else {
                    synchronized (state) {
                        if (controlledStop(state)) {
                            return;
                        }
                        List<String> options = new ArrayList<>();
                        arguments.path("options").forEach(option -> options.add(option.asText()));
                        state.pending = new PendingQuestion(call.callId(), question, options);
                        state.pendingCall = call;
                        state.lastPendingQuestion = state.pending;
                        enterWaiting(state, RunStatus.WAITING_INPUT, question);
                        event(state, RunEvent.Type.WAITING_INPUT, Map.of("callId", call.callId()));
                        safeEmit(listener, RunStreamChunk.waitingInput(question, options));
                    }
                    return;
                }
            }
            if (outcome == null && UpdatePlanTool.NAME.equals(call.name())) {
                try {
                    var arguments = ToolJson.parse(call.argumentsJson());
                    int expectedRevision = arguments.path("expectedRevision").asInt(-1);
                    int goalVersion = arguments.path("goalVersion").asInt(-1);
                    String reason = arguments.path("reason").asText("");
                    List<TaskStep> newSteps = new ArrayList<>();
                    if (arguments.has("steps") && arguments.get("steps").isArray()) {
                        for (var stepNode : arguments.get("steps")) {
                            String stepId = stepNode.path("stepId").asText("");
                            String title = stepNode.path("title").asText("");
                            String desc = stepNode.path("description").asText("");
                            String statusStr = stepNode.path("status").asText("PENDING");
                            TaskStep.Status status = null;
                            try {
                                status = TaskStep.Status.valueOf(statusStr.toUpperCase(Locale.ROOT));
                            } catch (Exception ignored) {
                            }
                            List<String> evidenceRefs = new ArrayList<>();
                            if (stepNode.has("evidenceRefs") && stepNode.get("evidenceRefs").isArray()) {
                                for (var refNode : stepNode.get("evidenceRefs")) {
                                    evidenceRefs.add(refNode.asText());
                                }
                            }
                            String stepReason = stepNode.path("reason").asText("");
                            if (status == null) {
                                outcome = new ToolOutcome.Failure("INVALID_STATUS", "Invalid status: " + statusStr, false);
                                break;
                            }
                            try {
                                newSteps.add(new TaskStep(stepId, title, desc, status, evidenceRefs, stepReason));
                            } catch (IllegalArgumentException e) {
                                outcome = new ToolOutcome.Failure("INVALID_STEP", e.getMessage(), false);
                                break;
                            }
                        }
                    }
                    if (outcome == null) {
                        Set<String> knownEvidence = state.resources != null ? state.resources.knownEvidenceIds() : Set.of();
                        int currentGoalVersion = state.taskBrief != null ? state.taskBrief.goalVersion() : 1;
                        TaskPlan.ValidationResult val = state.taskPlan.validateUpdate(expectedRevision, currentGoalVersion, newSteps, knownEvidence);
                        if (!val.valid()) {
                            outcome = new ToolOutcome.Failure(val.code(), val.message(), false);
                        } else {
                            state.taskPlan = state.taskPlan.withNextRevision(reason, newSteps);
                            publish(state);
                            event(state, RunEvent.Type.PLAN_UPDATED, Map.of("revision", state.taskPlan.planRevision(), "reason", reason));
                            if (listener != null) {
                                List<Map<String, Object>> stepsData = newSteps.stream().map(s -> Map.<String, Object>of(
                                        "stepId", s.stepId(),
                                        "title", s.title(),
                                        "status", s.status().name(),
                                        "evidenceRefs", s.evidenceRefs()
                                )).toList();
                                safeEmit(listener, RunStreamChunk.planUpdated(state.taskPlan.planRevision(), state.taskPlan.goalVersion(), reason, stepsData));
                            }
                            outcome = new ToolOutcome.Success(ToolJson.write(Map.of(
                                    "ok", true,
                                    "planRevision", state.taskPlan.planRevision(),
                                    "message", "Plan updated successfully to revision " + state.taskPlan.planRevision()
                            )));
                        }
                    }
                } catch (Exception e) {
                    outcome = new ToolOutcome.Failure("INVALID_ARGUMENT", "Failed to parse updatePlan arguments: " + e.getMessage(), false);
                }
            }
            if (outcome == null && !ClarificationTool.NAME.equals(call.name()) && !UpdatePlanTool.NAME.equals(call.name())) {
                Tool registeredTool = state.catalog.find(call.name()).orElse(null);
                if (registeredTool != null && registeredTool.definition().isHighRisk()) {
                    String approvalId = UUID.randomUUID().toString();
                    String description = "High-risk action '" + call.name() + "' requires human approval.";
                    ApprovalRequest req = new ApprovalRequest(approvalId, call.callId(), call.name(), call.argumentsJson(), description, Instant.now());
                    synchronized (state) {
                        if (controlledStop(state)) {
                            return;
                        }
                        state.pendingApproval = req;
                        state.pendingHighRiskCall = call;
                        state.pendingHighRiskCalls = calls;
                        state.pendingHighRiskIndex = index;
                        enterWaiting(state, RunStatus.WAITING_APPROVAL, description);
                        event(state, RunEvent.Type.APPROVAL_REQUESTED, Map.of(
                                "approvalId", approvalId,
                                "callId", call.callId(),
                                "tool", call.name(),
                                "argumentsSha256", digest(call.argumentsJson())
                        ));
                        safeEmit(listener, RunStreamChunk.waitingApproval(req));
                    }
                    return;
                }
            }
            if (outcome == null) {
                event(state, RunEvent.Type.TOOL_STARTED, Map.of("callId", call.callId(), "tool", call.name(),
                        "argumentsSha256", digest(call.argumentsJson())));
                safeEmit(listener, RunStreamChunk.toolStarted(call.callId(), call.name(), Map.of("callId", call.callId())));
                ToolContext context = new ToolContext(state.id, state.request.workspace(),
                        Instant.now().plus(remaining(state)), state::isCancellationRequested,
                        artifact -> state.artifacts.add(artifact), state.resources);
                long toolStart = nanoTime.getAsLong();
                try {
                    state.currentToolThread.set(Thread.currentThread());
                    try {
                        outcome = state.executor.execute(call, context);
                    } finally {
                        state.currentToolThread.set(null);
                        state.toolNanos += Math.max(0L, nanoTime.getAsLong() - toolStart);
                    }
                } catch (CancellationException | ActiveTimeExceededException ex) {
                    boolean cancelled = ex instanceof CancellationException;
                    event(state, RunEvent.Type.TOOL_FINISHED, Map.of("callId", call.callId(), "tool", call.name(),
                        "outcome", cancelled ? "INTERRUPTED_CANCELLED" : "INTERRUPTED_ACTIVE_TIME_LIMIT"));
                    finish(state, cancelled ? RunStatus.CANCELLED : RunStatus.STOPPED,
                            cancelled ? "CANCELLED" : "ACTIVE_TIME_LIMIT", "Tool interrupted by a runtime control condition");
                    skipped(state, calls, index + 1);
                    return;
                } catch (RuntimeException ex) {
                    if (state.isCancellationRequested()) {
                        event(state, RunEvent.Type.TOOL_FINISHED, Map.of("callId", call.callId(), "tool", call.name(),
                                "outcome", "INTERRUPTED_CANCELLED"));
                        finish(state, RunStatus.CANCELLED, "CANCELLED", "Tool interrupted by task cancellation");
                        skipped(state, calls, index + 1);
                        return;
                    }
                    throw ex;
                }
            }
            accept(state, call, outcome, listener);
            if (controlledStop(state)) {
                skipped(state, calls, index + 1);
                return;
            }
            if (outcome instanceof ToolOutcome.Failure failure && failure.terminal()) {
                finish(state, RunStatus.FAILED, failure.code(), failure.message());
            } else if (!mixed && state.errors >= state.spec.limits().maxConsecutiveToolErrors()) {
                finish(state, RunStatus.STOPPED, "TOOL_ERROR_LIMIT", "Too many consecutive correctable tool errors");
            }
            if (state.status != RunStatus.RUNNING) {
                skipped(state, calls, index + 1);
                return;
            }
        }
        if (mixed && state.errors >= state.spec.limits().maxConsecutiveToolErrors()) {
            finish(state, RunStatus.STOPPED, "TOOL_ERROR_LIMIT", "Too many consecutive correctable tool errors");
        }
    }

    private void accept(State state, ToolCall call, ToolOutcome outcome, Consumer<RunStreamChunk> listener) {
        state.observations.add(new RunSnapshot.Observation(call, outcome));
        state.errors = outcome instanceof ToolOutcome.Failure ? state.errors + 1 : 0;
        state.session.appendToolResult(call.callId(), outcome);
        String outcomeCode = outcome instanceof ToolOutcome.Failure failure ? failure.code() : "SUCCESS";
        Map<String, Object> toolPayload = new LinkedHashMap<>();
        toolPayload.put("callId", call.callId());
        toolPayload.put("tool", call.name());
        toolPayload.put("outcome", outcomeCode);
        if (outcome instanceof ToolOutcome.Failure failure && failure.message() != null) {
            toolPayload.put("error", failure.message());
        }
        event(state, RunEvent.Type.TOOL_FINISHED, toolPayload);
        safeEmit(listener, RunStreamChunk.toolFinished(call.callId(), call.name(), outcomeCode));
    }

    private void complete(State state, String answer, Consumer<RunStreamChunk> listener) throws IOException {
        state.resources.verifyInputs(() -> {
            if (state.isCancellationRequested()) throw new CancellationException();
            if (remaining(state).compareTo(Duration.ZERO) <= 0) throw new ActiveTimeExceededException();
        });
        CompletionCheck.Result check = state.spec.completionCheck().check(publish(state), answer);
        if (controlledStop(state)) {
            return;
        }

        check = verifyTaskBriefDeliverables(state, check);

        if (check.complete()) {
            state.wasResumedFromClarification = false;
            finish(state, RunStatus.COMPLETED, "COMPLETED", answer != null ? answer : "");
            return;
        }

        boolean allowSubDialogue = state.policy.clarificationPolicy() == ExecutionPolicy.ClarificationPolicy.ALLOW_SUB_DIALOGUE
                || check.status() == CompletionCheck.Result.Status.INTERMEDIATE_EXPLANATION;

        if (allowSubDialogue && state.wasResumedFromClarification && state.clarificationSubDialogueTurns < 3) {
            state.clarificationSubDialogueTurns++;
            state.wasResumedFromClarification = false;
            synchronized (state) {
                if (controlledStop(state)) {
                    return;
                }
                List<String> options = state.lastPendingQuestion != null ? state.lastPendingQuestion.options() : List.of();
                state.pending = new PendingQuestion(UUID.randomUUID().toString(), answer, options);
                enterWaiting(state, RunStatus.WAITING_INPUT, answer);
                state.reason = "CLARIFICATION_SUB_DIALOGUE";
                event(state, RunEvent.Type.WAITING_INPUT, Map.of("subDialogue", true, "turn", state.clarificationSubDialogueTurns));
                safeEmit(listener, RunStreamChunk.waitingInput(answer, options));
            }
            return;
        }

        if (state.corrections++ == 0) {
            state.session.appendRuntimeFeedback(ToolJson.write(Map.of("code", "INCOMPLETE_DELIVERABLE",
                    "feedback", check.feedback())));
            event(state, RunEvent.Type.DELIVERABLE_RETRY, Map.of("completionRepair", true, "feedback", check.feedback()));
        } else {
            finish(state, RunStatus.STOPPED, "INCOMPLETE_DELIVERABLE", check.feedback());
        }
    }

    private static boolean isControlTool(String toolName) {
        return ClarificationTool.NAME.equals(toolName) || UpdatePlanTool.NAME.equals(toolName);
    }

    private CompletionCheck.Result verifyTaskBriefDeliverables(State state, CompletionCheck.Result currentResult) {
        if (state.taskBrief == null) {
            return currentResult;
        }
        List<String> deliverables = state.taskBrief.deliverables();
        Set<Artifact> allArtifacts = new LinkedHashSet<>(state.artifacts);
        if (state.resources != null) {
            allArtifacts.addAll(state.resources.allArtifacts());
        }

        for (Artifact artifact : allArtifacts) {
            if (artifact.goalVersion() < state.taskBrief.goalVersion()) {
                return CompletionCheck.Result.incomplete(
                        "STALE_GOAL: Artifact '" + artifact.id() + "' was produced for goalVersion "
                                + artifact.goalVersion() + ", but current goalVersion is " + state.taskBrief.goalVersion()
                                + ". Please regenerate the deliverable for the revised goal.");
            }
        }

        if (!deliverables.isEmpty()) {
            for (String deliverable : deliverables) {
                boolean satisfied = false;
                Artifact emptyCandidate = null;
                Artifact missingFileCandidate = null;

                for (Artifact a : allArtifacts) {
                    if (matchesDeliverable(a, deliverable)) {
                        Path path = a.path();
                        if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
                            try {
                                if (Files.size(path) > 0) {
                                    satisfied = true;
                                    break;
                                } else {
                                    emptyCandidate = a;
                                }
                            } catch (IOException e) {
                                emptyCandidate = a;
                            }
                        } else {
                            missingFileCandidate = a;
                        }
                    }
                }

                if (satisfied) {
                    continue;
                }

                if (emptyCandidate != null) {
                    return CompletionCheck.Result.incomplete(
                            "EMPTY_DELIVERABLE: Required deliverable '" + deliverable
                                    + "' (artifact '" + emptyCandidate.id()
                                    + "') is empty (0 bytes). Please generate non-empty content before completing.");
                }
                if (missingFileCandidate != null) {
                    return CompletionCheck.Result.incomplete(
                            "MISSING_DELIVERABLE: Required deliverable '" + deliverable
                                    + "' was referenced as artifact '" + missingFileCandidate.id()
                                    + "', but physical file does not exist at '" + missingFileCandidate.path()
                                    + "'. Please generate it before completing.");
                }
                return CompletionCheck.Result.incomplete(
                        "MISSING_DELIVERABLE: Required deliverable '" + deliverable
                                + "' is not yet generated in the workspace. Please generate it before completing.");
            }
        }

        return currentResult;
    }

    static boolean matchesDeliverable(Artifact artifact, String deliverable) {
        if (artifact == null || deliverable == null || deliverable.isBlank()) {
            return false;
        }
        String expected = deliverable.trim();
        if (artifact.id() != null && artifact.id().equalsIgnoreCase(expected)) {
            return true;
        }

        Path path = artifact.path();
        if (path == null) {
            return false;
        }

        Path fileNamePath = path.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "";

        if (!fileName.isEmpty() && fileName.equalsIgnoreCase(expected)) {
            return true;
        }

        String baseName = getBaseFileName(fileName);
        if (!baseName.isEmpty() && baseName.equalsIgnoreCase(expected)) {
            return true;
        }

        String expectedBaseName = getBaseFileName(expected);
        if (artifact.id() != null && !expectedBaseName.isEmpty() && artifact.id().equalsIgnoreCase(expectedBaseName)) {
            return true;
        }

        try {
            Path expectedPath = Path.of(expected);
            if (expectedPath.getNameCount() > 1 && path.normalize().endsWith(expectedPath.normalize())) {
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private static String getBaseFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    private String protocolError(State state, ModelResponse response) {
        if (response == null || response.finishReason() == null) {
            return "MODEL_PROTOCOL_ERROR";
        }
        if (response.finishReason() == ModelResponse.FinishReason.LENGTH) {
            return "MODEL_RESPONSE_TRUNCATED";
        }
        if (response.finishReason() == ModelResponse.FinishReason.REFUSED
                || response.finishReason() == ModelResponse.FinishReason.ERROR) {
            return "MODEL_RESPONSE_REJECTED";
        }
        Set<String> ids = new HashSet<>();
        for (ToolCall call : response.toolCalls()) {
            if (call == null || call.callId() == null || call.callId().isBlank() || call.callId().length() > 256
                    || !ids.add(call.callId()) || state.callIds.contains(call.callId())
                    || call.name() == null || !call.name().matches("[a-zA-Z0-9_-]{1,64}")
                    || call.argumentsJson() == null || call.argumentsJson().length() > 65536) {
                return "MODEL_PROTOCOL_ERROR";
            }
        }
        state.callIds.addAll(ids);
        if (response.toolCalls().isEmpty() && (response.text() == null || response.text().isBlank()
                || response.finishReason() == ModelResponse.FinishReason.TOOL_CALLS)) {
            return "EMPTY_MODEL_RESPONSE";
        }
        return null;
    }

    private void skipped(State state, List<ToolCall> calls, int from) {
        for (int i = from; i < calls.size(); i++) {
            event(state, RunEvent.Type.TOOL_FINISHED, Map.of("callId", calls.get(i).callId(),
                    "tool", calls.get(i).name(), "outcome", "NOT_EXECUTED_" + state.status.name()));
        }
    }

    private boolean controlledStop(State state) {
        if (state.isCancellationRequested()) {
            finish(state, RunStatus.CANCELLED, "CANCELLED", "Task cancelled; existing artifacts were retained");
            return true;
        }
        if (remaining(state).isZero() || remaining(state).isNegative()) {
            finish(state, RunStatus.STOPPED, "ACTIVE_TIME_LIMIT", "Active processing time exhausted");
            return true;
        }
        return false;
    }

    private void finish(State state, RunStatus status, String reason, String message) {
        synchronized (state) {
            if (state.status == RunStatus.CANCELLED) {
                return;
            }
            if (state.isCancellationRequested()) {
                status = RunStatus.CANCELLED;
                reason = "CANCELLED";
                message = "Task cancelled; existing artifacts were retained";
            } else if (status == RunStatus.COMPLETED) {
                if (remaining(state).isZero() || remaining(state).isNegative()) {
                    status = RunStatus.STOPPED;
                    reason = "ACTIVE_TIME_LIMIT";
                    message = "Active processing time exhausted";
                }
            }
            if (!status.isWaiting()) {
                exitWaiting(state);
            }
            state.status = status;
            state.reason = reason;
            state.message = message;
            state.pending = null;
            state.pendingCall = null;
            state.pendingApproval = null;
            state.pendingHighRiskCall = null;
            if (state.resources != null) {
                state.resources.cleanupTempFiles();
            }
            if (state.message != null && !state.message.isBlank() && (status == RunStatus.COMPLETED || status == RunStatus.WAITING_INPUT)) {
                String assistantMsg = state.message;
                state.request.conversationId().ifPresent(cid ->
                        memory.append(cid, ChatMessage.assistant(assistantMsg)));
            }
            publish(state);
        }
    }

    private void cancelWaiting(State state) {
        cancelWaiting(state, "CANCELLED", "Task cancelled");
    }

    private void cancelWaiting(State state, String reason, String message) {
        finish(state, RunStatus.CANCELLED, reason, message);
        release(state);
        emitFinished(state);
        publish(state);
    }

    private void emitFinished(State state) {
        synchronized (state) {
            if (!state.traceReady || state.finishedEvent) {
                return;
            }
            state.finishedEvent = true;
        }
        try {
            event(state, RunEvent.Type.RUN_FINISHED, Map.of("status", state.status.name(), "reason", state.reason));
        } catch (RuntimeException ex) {
            failWithoutEvent(state, "STORAGE_FAILED", "The final run event could not be recorded");
        }
    }

    private void failWithoutEvent(State state, String reason, String message) {
        state.status = RunStatus.FAILED;
        state.reason = reason;
        state.message = message;
        state.pending = null;
        state.pendingCall = null;
        state.pendingApproval = null;
        state.pendingHighRiskCall = null;
        state.pendingHighRiskCalls = null;
        publish(state);
    }

    private void event(State state, RunEvent.Type type, Map<String, Object> payload) {
        Map<String, Object> details = new LinkedHashMap<>(payload);
        RunMetrics metrics = metrics(state);
        details.put("modelRequests", metrics.modelRequests());
        details.put("toolCalls", metrics.toolCalls());
        details.put("activeMillis", metrics.activeTime().toMillis());
        details.put("modelMillis", metrics.modelTime().toMillis());
        details.put("toolMillis", metrics.toolTime().toMillis());
        details.put("waitingMillis", metrics.waitingTime().toMillis());
        details.put("usage", metrics.tokenUsage().<Object>map(usage -> Map.of(
                "inputTokens", usage.inputTokens(), "outputTokens", usage.outputTokens())).orElse("unknown"));
        if (state.spec != null && !details.containsKey("agentId")) {
            details.put("agentId", state.spec.id());
        }
        events.publish(new RunEvent(++state.sequence, state.id, Instant.now(), type, ToolJson.write(details)));
    }

    private RunMetrics metrics(State state) {
        Optional<ModelResponse.TokenUsage> usage = state.usageKnown && state.modelRequests > 0
                && state.usageSamples == state.modelRequests
                ? Optional.of(new ModelResponse.TokenUsage(state.inputTokens, state.outputTokens)) : Optional.empty();
        return new RunMetrics(
                state.modelRequests,
                state.toolCalls,
                Duration.ofNanos(elapsed(state)),
                Duration.ofNanos(state.modelNanos),
                Duration.ofNanos(state.toolNanos),
                Duration.ofNanos(waitingElapsed(state)),
                usage
        );
    }

    private RunSnapshot publish(State state) {
        state.snapshot = new RunSnapshot(state.id, state.spec.id(), state.currentGoal, state.status,
                state.observations, state.artifacts,
                metrics(state),
                Optional.ofNullable(state.pending),
                Optional.ofNullable(state.taskBrief),
                Optional.ofNullable(state.taskPlan),
                Optional.ofNullable(state.pendingApproval));
        return state.snapshot;
    }

    private void recordUsage(State state, ModelResponse response) {
        if (response == null || response.usage() == null || response.usage().isEmpty()) {
            state.usageKnown = false;
            return;
        }
        var usage = response.usage().orElseThrow();
        if (usage.inputTokens() < 0 || usage.outputTokens() < 0) {
            state.usageKnown = false;
            return;
        }
        state.inputTokens = Math.addExact(state.inputTokens, usage.inputTokens());
        state.outputTokens = Math.addExact(state.outputTokens, usage.outputTokens());
        state.usageSamples++;
    }

    private RunResult result(State state) {
        return new RunResult(state.snapshot, state.message, state.reason);
    }

    private long elapsed(State state) {
        return state.active ? state.accumulatedNanos + (nanoTime.getAsLong() - state.startedAt)
                : state.accumulatedNanos;
    }

    private Duration remaining(State state) {
        return state.spec.limits().activeTimeout().minusNanos(elapsed(state));
    }

    private void release(State state) {
        synchronized (state) {
            if (state.released) {
                return;
            }
            state.released = true;
        }
        try {
            try {
                if (state.session != null) {
                    state.session.close();
                }
            } finally {
                if (state.resources != null) {
                    state.resources.close();
                }
            }
        } catch (Exception ex) {
            failWithoutEvent(state, "RESOURCE_CLOSE_FAILED", "Task resources could not be closed cleanly");
        } finally {
            state.session = null;
            state.resources = null;
        }
    }

    private State require(UUID runId) {
        State state = runs.get(runId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown run; process restart recovery is not supported");
        }
        return state;
    }

    private void acquire() {
        if (!owner.tryLock()) {
            throw new IllegalStateException("A task is already being executed");
        }
    }

    private void unlockOwner() {
        State state = current;
        if (state == null) {
            owner.unlock();
            return;
        }
        synchronized (state) {
            try {
                if (state.status.isWaiting() && state.isCancellationRequested()) {
                    cancelWaiting(state);
                }
            } finally {
                owner.unlock();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        State state = current;
        if (state != null && !terminal(state.snapshot.status())) {
            cancel(state.id);
        }
    }

    private static boolean terminal(RunStatus status) {
        return status != RunStatus.RUNNING && !status.isWaiting();
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) < 0 ? left : right;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConnectionError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException
                    || cause instanceof java.net.PortUnreachableException
                    || cause instanceof java.net.SocketException
                    || cause instanceof java.io.EOFException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientModelError(State state, Throwable error) {
        if (isTimeout(error) || isConnectionError(error)) {
            return true;
        }
        return state.session != null && state.session.isTransientFailure(error);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @FunctionalInterface
    private interface StartAction {
        void run() throws IOException;
    }

    private final class State {
        private final UUID id = UUID.randomUUID();
        private final AgentSpec spec;
        private final RunRequest request;
        private final ToolExecutor executor;
        private final ToolCatalog catalog;
        private final ExecutionPolicy policy;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final List<RunSnapshot.Observation> observations = new ArrayList<>();
        private final List<Artifact> artifacts = new ArrayList<>();
        private final Set<String> callIds = new HashSet<>();
        private volatile RunSnapshot snapshot;
        private volatile RunStatus status = RunStatus.RUNNING;
        private ModelSession session;
        private WorkspaceSession resources;
        private PendingQuestion pending;
        private PendingQuestion lastPendingQuestion;
        private ToolCall pendingCall;
        private ApprovalRequest pendingApproval;
        private ToolCall pendingHighRiskCall;
        private List<ToolCall> pendingHighRiskCalls;
        private int pendingHighRiskIndex;
        private Instant waitingSince;
        private Instant lastAccessedAt = Instant.now();
        private String currentGoal;
        private boolean wasResumedFromClarification;
        private int clarificationSubDialogueTurns;
        private String reason = "";
        private String message = "";
        private String lastFingerprint;
        private long sequence;
        private long accumulatedNanos;
        private long startedAt;
        private long modelNanos;
        private long toolNanos;
        private long waitingNanos;
        private long waitingStartedAtNanos;
        private boolean waitingTimingActive;
        private long inputTokens;
        private long outputTokens;
        private boolean active;
        private boolean traceReady;
        private boolean finishedEvent;
        private boolean usageKnown = true;
        private int modelRequests;
        private int usageSamples;
        private int toolCalls;
        private int repeated;
        private int errors;
        private int corrections;
        private TaskBrief taskBrief;
        private TaskPlan taskPlan;
        private int maxCorrections = 2;
        private final AtomicReference<Thread> runnerThread = new AtomicReference<>();
        private final AtomicReference<Thread> currentToolThread = new AtomicReference<>();
        private boolean released;

        private void abortActiveOperations() {
            ModelSession s = this.session;
            if (s != null) {
                try {
                    s.cancel();
                } catch (Exception ignored) {
                }
            }
            Thread toolThread = this.currentToolThread.get();
            if (toolThread != null && toolThread != Thread.currentThread()) {
                try {
                    toolThread.interrupt();
                } catch (Exception ignored) {
                }
            }
        }

        private boolean isCancellationRequested() {
            return closed || cancelled.get() || request.cancellationRequested().getAsBoolean();
        }

        private State(AgentSpec spec, RunRequest request, ToolExecutor executor, ToolCatalog catalog, ExecutionPolicy policy) {
            this.spec = spec;
            this.request = request;
            this.executor = executor;
            this.catalog = catalog;
            this.policy = policy;
            this.currentGoal = request.goal();
            this.taskBrief = request.initialBrief().orElseGet(() -> TaskBrief.of(request.goal()));
            this.taskPlan = TaskPlan.empty(this.taskBrief.goalVersion());
            publish(this);
        }
    }
}
