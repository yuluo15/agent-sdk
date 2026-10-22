package com.agent.agentcorelab.agent;

import com.agent.agentcorelab.agent.run.RunLimits;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunLimitsTests {

    @Test
    void acceptsPositiveLimits() {
        RunLimits limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
        assertEquals(16, limits.maxModelRequests());
        assertEquals(Duration.ofSeconds(300), limits.activeTimeout());
    }

    @Test
    void rejectsNonPositiveRequestLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(0, 30, Duration.ofSeconds(60), Duration.ofSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(16, -1, Duration.ofSeconds(60), Duration.ofSeconds(300)));
    }

    @Test
    void rejectsContextAndToolLimitsThatDisableControls() {
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(16, 30,
                Duration.ofSeconds(60), Duration.ofSeconds(300), 4096, 4096, 3, 3, 16384));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(16, 30,
                Duration.ofSeconds(60), Duration.ofSeconds(300), 32768, 4096, 1, 3, 16384));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(16, 30,
                Duration.ofSeconds(60), Duration.ofSeconds(300), 32768, 4096, 3, 0, 16384));
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(16, 30, Duration.ZERO, Duration.ofSeconds(300)));
        assertThrows(IllegalArgumentException.class,
                () -> new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(-1)));
    }

    @Test
    void acceptsConfigurableModelRetries() {
        RunLimits limits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300),
                32768, 4096, 3, 3, 16384, Duration.ofMinutes(15), 0);
        assertEquals(0, limits.maxModelRetries());

        RunLimits defaultLimits = new RunLimits(16, 30, Duration.ofSeconds(60), Duration.ofSeconds(300));
        assertEquals(1, defaultLimits.maxModelRetries());

        assertThrows(IllegalArgumentException.class, () -> new RunLimits(16, 30,
                Duration.ofSeconds(60), Duration.ofSeconds(300), 32768, 4096, 3, 3, 16384, Duration.ofMinutes(15), -1));
    }
}
