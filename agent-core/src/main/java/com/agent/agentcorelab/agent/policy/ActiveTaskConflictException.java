package com.agent.agentcorelab.agent.policy;

import com.agent.agentcorelab.agent.AgentRunner;
import com.agent.agentcorelab.agent.run.PendingQuestion;
import com.agent.agentcorelab.agent.run.RunStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain exception thrown when a new task is submitted to an {@link AgentRunner}
 * that already has an active task (either {@link RunStatus#RUNNING} or {@link RunStatus#WAITING_INPUT})
 * and the active task policy does not permit preemption or automatic resumption.
 * <p>
 * Extends {@link IllegalStateException} for backwards compatibility with existing exception handlers.
 */
public final class ActiveTaskConflictException extends IllegalStateException {

    private final UUID activeRunId;
    private final RunStatus activeStatus;
    private final PendingQuestion pendingQuestion;

    public ActiveTaskConflictException(UUID activeRunId, RunStatus activeStatus, PendingQuestion pendingQuestion) {
        super(formatMessage(activeRunId, activeStatus, pendingQuestion));
        this.activeRunId = Objects.requireNonNull(activeRunId, "activeRunId");
        this.activeStatus = Objects.requireNonNull(activeStatus, "activeStatus");
        this.pendingQuestion = pendingQuestion;
    }

    public UUID activeRunId() {
        return activeRunId;
    }

    public RunStatus activeStatus() {
        return activeStatus;
    }

    public Optional<PendingQuestion> pendingQuestion() {
        return Optional.ofNullable(pendingQuestion);
    }

    private static String formatMessage(UUID activeRunId, RunStatus activeStatus, PendingQuestion pendingQuestion) {
        StringBuilder sb = new StringBuilder("Runner has an active task [")
                .append(activeRunId)
                .append("] in status ")
                .append(activeStatus);
        if (pendingQuestion != null) {
            sb.append(" waiting for answer to: \"").append(pendingQuestion.question()).append("\"");
        }
        return sb.toString();
    }
}
