package com.agent.agentcorelab.trace;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CompositeRunEventSinkTests {

    @Test
    void delegatesToPrimaryAndAllListeners() {
        List<RunEvent> primaryEvents = new ArrayList<>();
        List<RunEvent> listener1Events = new ArrayList<>();
        List<RunEvent> listener2Events = new ArrayList<>();

        RunEventSink primary = primaryEvents::add;
        RunEventListener listener1 = listener1Events::add;
        RunEventListener listener2 = listener2Events::add;

        CompositeRunEventSink composite = new CompositeRunEventSink(primary, List.of(listener1, listener2));
        RunEvent event = new RunEvent(1, UUID.randomUUID(), Instant.now(), RunEvent.Type.RUN_STARTED, "{}");

        composite.publish(event);

        assertEquals(1, primaryEvents.size());
        assertEquals(event, primaryEvents.getFirst());
        assertEquals(1, listener1Events.size());
        assertEquals(event, listener1Events.getFirst());
        assertEquals(1, listener2Events.size());
        assertEquals(event, listener2Events.getFirst());
        assertSame(primary, composite.primary());
        assertEquals(2, composite.listeners().size());
    }

    @Test
    void suppressesListenerExceptionsWithoutInterruptingOthers() {
        AtomicBoolean primaryCalled = new AtomicBoolean(false);
        AtomicBoolean secondListenerCalled = new AtomicBoolean(false);

        RunEventSink primary = e -> primaryCalled.set(true);
        RunEventListener throwingListener = e -> {
            throw new RuntimeException("Simulated metric failure");
        };
        RunEventListener healthyListener = e -> secondListenerCalled.set(true);

        CompositeRunEventSink composite = new CompositeRunEventSink(primary, List.of(throwingListener, healthyListener));
        RunEvent event = new RunEvent(1, UUID.randomUUID(), Instant.now(), RunEvent.Type.RUN_FINISHED, "{}");

        assertDoesNotThrow(() -> composite.publish(event));
        assertTrue(primaryCalled.get());
        assertTrue(secondListenerCalled.get());
    }
}
