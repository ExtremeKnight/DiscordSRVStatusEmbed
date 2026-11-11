package com.exenova.link.watchdog;

import org.junit.jupiter.api.Test;

import static com.exenova.link.watchdog.HealthLevel.*;
import static org.junit.jupiter.api.Assertions.*;

class HealthLevelTest {
    private final Thresholds t = Thresholds.defaults();

    @Test
    void healthyServerIsOk() {
        assertEquals(OK, evaluate(20.0, 40, 30, t));
        assertEquals(OK, evaluate(20.0, 40, -1, t), "unknown CPU is ignored");
    }

    @Test
    void lowTpsRaisesWarningThenCritical() {
        assertEquals(WARNING, evaluate(17.0, 40, 30, t));
        assertEquals(CRITICAL, evaluate(14.0, 40, 30, t));
        assertEquals(WARNING, evaluate(18.0, 40, 30, t), "at the warn threshold");
        assertEquals(CRITICAL, evaluate(15.0, 40, 30, t), "at the critical threshold");
    }

    @Test
    void highRamOrCpuRaisesLevel() {
        assertEquals(WARNING, evaluate(20.0, 90, 30, t));
        assertEquals(CRITICAL, evaluate(20.0, 96, 30, t));
        assertEquals(CRITICAL, evaluate(20.0, 40, 97, t));
    }

    @Test
    void worstMetricWins() {
        // Good TPS, warning RAM, critical CPU -> CRITICAL.
        assertEquals(CRITICAL, evaluate(20.0, 90, 99, t));
        // Critical TPS dominates healthy RAM/CPU.
        assertEquals(CRITICAL, evaluate(10.0, 10, 10, t));
    }
}
