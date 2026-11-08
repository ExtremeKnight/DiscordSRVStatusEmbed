package com.exenova.link.reports;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory report wizard state with explicit lifecycle operations.
 * Thread-safe: the "awaiting details" set is read and modified from
 * AsyncPlayerChatEvent (an async thread) while the main thread updates the
 * rest, so every collection here is concurrent.
 */
public final class ReportStateStore {
    private final Map<UUID, UUID> targets = new ConcurrentHashMap<>();
    private final Map<UUID, String> reasons = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingDetails = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> started = new ConcurrentHashMap<>();

    public Map<UUID, UUID> targets() { return targets; }
    public Map<UUID, String> reasons() { return reasons; }
    public Set<UUID> awaitingDetails() { return awaitingDetails; }
    public Map<UUID, Long> started() { return started; }

    /** Starts (or restarts) a report by {@code reporter} against {@code target}. */
    public void begin(UUID reporter, UUID target, long now) {
        clear(reporter);
        targets.put(reporter, target);
        started.put(reporter, now);
    }

    public void clear(UUID player) {
        targets.remove(player);
        reasons.remove(player);
        awaitingDetails.remove(player);
        started.remove(player);
    }

    /** Clears every report that was started before {@code cutoff}; returns how many were cleared. */
    public int expireBefore(long cutoff) {
        int expired = 0;
        for (Map.Entry<UUID, Long> entry : started.entrySet()) {
            if (entry.getValue() < cutoff) {
                clear(entry.getKey());
                expired++;
            }
        }
        return expired;
    }

    public void clearAll() {
        targets.clear();
        reasons.clear();
        awaitingDetails.clear();
        started.clear();
    }
}
