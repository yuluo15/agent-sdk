package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.workspace.Workspace;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Manages isolated {@link AgentRunner} instances per user, session, or dialogue conversation.
 * <p>
 * Decouples downstream applications from a single global runner instance, providing
 * session-level isolation, bounded LRU capacity management, active run protection,
 * and idle eviction.
 */
public interface SessionRunnerManager extends AutoCloseable {

    /**
     * Retrieves the existing runner for {@code sessionId}, or creates a fresh one using the runner factory.
     * Updates the session's access time for LRU tracking.
     *
     * @param sessionId unique session or conversation identifier
     * @return the isolated runner for this session
     */
    AgentRunner getOrCreate(String sessionId);

    /**
     * Retrieves the runner for {@code sessionId} if present, updating its access time.
     *
     * @param sessionId unique session identifier
     * @return optional containing the runner if found
     */
    Optional<AgentRunner> get(String sessionId);

    /**
     * Checks if a runner for {@code sessionId} is currently registered.
     */
    boolean contains(String sessionId);

    /**
     * Removes and closes the runner for {@code sessionId}, releasing all associated resources.
     *
     * @param sessionId unique session identifier
     * @return the removed runner if it existed
     */
    Optional<AgentRunner> remove(String sessionId);

    /**
     * Creates or retrieves an {@link AgentConversation} bound to this session's isolated runner.
     *
     * @param sessionId unique session identifier
     * @param spec      agent specification
     * @param workspace workspace configuration
     * @return conversational dialogue session
     */
    default AgentConversation getOrCreateConversation(String sessionId, AgentSpec spec, Workspace workspace) {
        return new AgentConversation(getOrCreate(sessionId), spec, workspace, sessionId);
    }

    /**
     * Evicts idle sessions that have had no access for longer than {@code idleTimeout}.
     * <p>
     * Active sessions currently executing in {@code RUNNING} status are strictly protected
     * and never evicted.
     *
     * @param idleTimeout maximum allowable idle duration
     * @return number of sessions evicted and closed
     */
    int evictIdle(Duration idleTimeout);

    /**
     * Evicts idle sessions using the default configured idle timeout.
     *
     * @return number of sessions evicted and closed
     */
    int evictIdle();

    /**
     * Prunes runs that have exceeded their waiting timeout across all managed sessions.
     *
     * @return total count of expired waiting runs pruned across all sessions
     */
    default int pruneExpiredRuns() {
        return 0;
    }

    /**
     * Returns the number of currently active sessions.
     */
    int sessionCount();

    /**
     * Returns the total cumulative count of sessions evicted due to capacity or idle timeout.
     */
    long evictedCount();

    /**
     * Returns an unmodifiable snapshot of all currently active session IDs.
     */
    Set<String> activeSessionIds();

    @Override
    void close();
}
