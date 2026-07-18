package me.example.statusembed.automation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe cooldown store keyed by automation and optional actor. */
public final class CooldownManager {
    private final Map<String, Long> nextAllowed = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, long cooldownSeconds) {
        if (cooldownSeconds <= 0) return true;
        long now = System.currentTimeMillis();
        long allowed = nextAllowed.getOrDefault(key, 0L);
        if (allowed > now) return false;
        nextAllowed.put(key, now + cooldownSeconds * 1000L);
        return true;
    }

    public void clear() { nextAllowed.clear(); }
}
