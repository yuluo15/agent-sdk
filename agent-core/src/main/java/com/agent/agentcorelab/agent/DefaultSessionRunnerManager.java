package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.run.RunSnapshot;
import com.agent.agentcorelab.agent.run.RunStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Default standard implementation of {@link SessionRunnerManager}.
 * <p>
 * Employs access-order LRU capacity management, protects active {@code RUNNING} sessions
 * against eviction, and cleans up idle runners using configurable timeouts.
 */
public final class DefaultSessionRunnerManager implements SessionRunnerManager {
    public static final int DEFAULT_MAX_SESSIONS = 1000;
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final java.util.function.Supplier<? extends AgentRunner> runnerSupplier;
    private final int maxSessions;
    private final Duration defaultIdleTimeout;
    private final LongSupplier nanoTime;
    private final ReentrantLock lock = new ReentrantLock();

    // Access-order LinkedHashMap for LRU tracking
    private final Map<String, SessionEntry> sessions;
    private final AtomicLong evictedCount = new AtomicLong(0);
    private volatile boolean closed = false;

    private static final class SessionEntry {
        final String sessionId;
        final AgentRunner runner;
        final long createdAtNanos;
        volatile long lastAccessNanos;

        SessionEntry(String sessionId, AgentRunner runner, long nowNanos) {
            this.sessionId = sessionId;
            this.runner = runner;
            this.createdAtNanos = nowNanos;
            this.lastAccessNanos = nowNanos;
        }

        void touch(long nowNanos) {
            this.lastAccessNanos = nowNanos;
        }

        boolean isRunning() {
            Optional<RunSnapshot> snapshot = runner.currentSnapshot();
            return snapshot.isPresent() && snapshot.get().status() == RunStatus.RUNNING;
        }

        void close() {
            if (runner instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public DefaultSessionRunnerManager(AgentRunnerFactory factory) {
        this(toSupplier(factory), DEFAULT_MAX_SESSIONS, DEFAULT_IDLE_TIMEOUT, System::nanoTime);
    }

    public DefaultSessionRunnerManager(AgentRunnerFactory factory, int maxSessions, Duration defaultIdleTimeout) {
        this(toSupplier(factory), maxSessions, defaultIdleTimeout, System::nanoTime);
    }

    public DefaultSessionRunnerManager(AgentRunnerFactory factory, int maxSessions, Duration defaultIdleTimeout, LongSupplier nanoTime) {
        this(toSupplier(factory), maxSessions, defaultIdleTimeout, nanoTime);
    }

    private static java.util.function.Supplier<AgentRunner> toSupplier(AgentRunnerFactory factory) {
        Objects.requireNonNull(factory, "AgentRunnerFactory cannot be null");
        return factory::create;
    }

    public DefaultSessionRunnerManager(java.util.function.Supplier<? extends AgentRunner> runnerSupplier, int maxSessions, Duration defaultIdleTimeout, LongSupplier nanoTime) {
        this.runnerSupplier = Objects.requireNonNull(runnerSupplier, "runnerSupplier cannot be null");
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive, got: " + maxSessions);
        }
        this.maxSessions = maxSessions;
        this.defaultIdleTimeout = Objects.requireNonNull(defaultIdleTimeout, "defaultIdleTimeout cannot be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime cannot be null");
        this.sessions = new LinkedHashMap<>(16, 0.75f, true);
    }

    @Override
    public AgentRunner getOrCreate(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId cannot be blank");
        }
        SessionEntry toClose = null;
        lock.lock();
        try {
            ensureOpen();
            long now = nanoTime.getAsLong();
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) {
                entry.touch(now);
                return entry.runner;
            }

            if (sessions.size() >= maxSessions) {
                toClose = evictOneLruCandidate();
                if (toClose == null) {
                    throw new IllegalStateException("SessionRunnerManager capacity limit (" + maxSessions
                            + ") reached and all active sessions are currently RUNNING. Cannot create session: " + sessionId);
                }
            }

            AgentRunner newRunner = runnerSupplier.get();
            SessionEntry newEntry = new SessionEntry(sessionId, newRunner, now);
            sessions.put(sessionId, newEntry);
            return newRunner;
        } finally {
            lock.unlock();
            if (toClose != null) {
                toClose.close();
            }
        }
    }

    @Override
    public Optional<AgentRunner> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            ensureOpen();
            SessionEntry entry = sessions.get(sessionId);
            if (entry != null) {
                entry.touch(nanoTime.getAsLong());
                return Optional.of(entry.runner);
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        lock.lock();
        try {
            return sessions.containsKey(sessionId);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<AgentRunner> remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        SessionEntry removed;
        lock.lock();
        try {
            removed = sessions.remove(sessionId);
        } finally {
            lock.unlock();
        }
        if (removed != null) {
            removed.close();
            return Optional.of(removed.runner);
        }
        return Optional.empty();
    }

    @Override
    public int evictIdle(Duration idleTimeout) {
        Objects.requireNonNull(idleTimeout, "idleTimeout cannot be null");
        long idleNanos = idleTimeout.toNanos();
        List<SessionEntry> toClose = new ArrayList<>();
        lock.lock();
        try {
            if (closed) {
                return 0;
            }
            long now = nanoTime.getAsLong();
            Iterator<Map.Entry<String, SessionEntry>> iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                SessionEntry entry = iterator.next().getValue();
                if (!entry.isRunning() && (now - entry.lastAccessNanos >= idleNanos)) {
                    iterator.remove();
                    toClose.add(entry);
                    evictedCount.incrementAndGet();
                }
            }
        } finally {
            lock.unlock();
        }

        for (SessionEntry entry : toClose) {
            entry.close();
        }
        return toClose.size();
    }

    @Override
    public int evictIdle() {
        return evictIdle(this.defaultIdleTimeout);
    }

    @Override
    public int pruneExpiredRuns() {
        List<AgentRunner> snapshotRunners;
        lock.lock();
        try {
            if (closed) {
                return 0;
            }
            snapshotRunners = sessions.values().stream().map(e -> e.runner).toList();
        } finally {
            lock.unlock();
        }

        int totalPruned = 0;
        for (AgentRunner runner : snapshotRunners) {
            totalPruned += runner.pruneExpiredRuns();
        }
        return totalPruned;
    }

    @Override
    public int sessionCount() {
        lock.lock();
        try {
            return sessions.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long evictedCount() {
        return evictedCount.get();
    }

    @Override
    public Set<String> activeSessionIds() {
        lock.lock();
        try {
            return Set.copyOf(sessions.keySet());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        List<SessionEntry> toClose;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(sessions.values());
            sessions.clear();
        } finally {
            lock.unlock();
        }

        for (SessionEntry entry : toClose) {
            entry.close();
        }
    }

    private SessionEntry evictOneLruCandidate() {
        Iterator<Map.Entry<String, SessionEntry>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SessionEntry> mapEntry = iterator.next();
            SessionEntry candidate = mapEntry.getValue();
            if (!candidate.isRunning()) {
                iterator.remove();
                evictedCount.incrementAndGet();
                return candidate;
            }
        }
        return null;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SessionRunnerManager has already been closed");
        }
    }
}
