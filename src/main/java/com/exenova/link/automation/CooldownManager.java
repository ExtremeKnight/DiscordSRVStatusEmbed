package com.exenova.link.automation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Thread-safe cooldown store keyed by automation and optional actor. */
public final class CooldownManager {
    private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public CooldownManager() { this(System::currentTimeMillis); }

    /** Visible for tests: lets the clock be controlled. */
    CooldownManager(LongSupplier clock) { this.clock = clock; }

    /**
     * Atomically checks and takes the cooldown for {@code key}. Two threads
     * racing on the same key (e.g. async chat and a main-thread event) can
     * never both succeed inside one cooldown window.
     */
    public boolean tryAcquire(String key, long cooldownSeconds) {
        if (cooldownSeconds <= 0) return true;
        long now = clock.getAsLong();
        AtomicBoolean acquired = new AtomicBoolean(false);
        nextAllowed.compute(key, (k, allowed) -> {
            if (allowed != null && allowed > now) return allowed;
            acquired.set(true);
            return now + cooldownSeconds * 1000L;
        });
        if (acquired.get() && nextAllowed.size() > 10_000) pruneExpired(now);
        return acquired.get();
    }

    /** Drops entries whose cooldown has already elapsed so per-player keys don't accumulate forever. */
    public void pruneExpired(long now) { nextAllowed.values().removeIf(allowed -> allowed <= now); }

    int size() { return nextAllowed.size(); }

    public void clear() { nextAllowed.clear(); }
}
