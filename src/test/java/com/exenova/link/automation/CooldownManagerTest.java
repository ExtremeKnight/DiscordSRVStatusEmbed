package com.exenova.link.automation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CooldownManagerTest {
    @Test
    void blocksUntilCooldownElapses() {
        AtomicLong now = new AtomicLong(0);
        CooldownManager cooldowns = new CooldownManager(now::get);

        assertTrue(cooldowns.tryAcquire("a", 5));
        assertFalse(cooldowns.tryAcquire("a", 5));
        assertTrue(cooldowns.tryAcquire("b", 5), "keys are independent");
        now.set(5_000);
        assertTrue(cooldowns.tryAcquire("a", 5));
    }

    @Test
    void zeroCooldownAlwaysPasses() {
        CooldownManager cooldowns = new CooldownManager(() -> 0);
        assertTrue(cooldowns.tryAcquire("a", 0));
        assertTrue(cooldowns.tryAcquire("a", 0));
        assertEquals(0, cooldowns.size());
    }

    @Test
    void pruneDropsExpiredEntries() {
        AtomicLong now = new AtomicLong(0);
        CooldownManager cooldowns = new CooldownManager(now::get);
        cooldowns.tryAcquire("a", 1);
        cooldowns.tryAcquire("b", 10);
        cooldowns.pruneExpired(2_000);
        assertEquals(1, cooldowns.size());
    }
}
