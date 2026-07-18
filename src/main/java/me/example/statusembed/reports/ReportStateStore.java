package me.example.statusembed.reports;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-memory report wizard state with explicit lifecycle operations. */
public final class ReportStateStore {
    private final Map<UUID, UUID> targets = new HashMap<>();
    private final Map<UUID, String> reasons = new HashMap<>();
    private final Set<UUID> awaitingDetails = new java.util.HashSet<>();
    private final Map<UUID, Long> started = new HashMap<>();

    public Map<UUID, UUID> targets() { return targets; }
    public Map<UUID, String> reasons() { return reasons; }
    public Set<UUID> awaitingDetails() { return awaitingDetails; }
    public Map<UUID, Long> started() { return started; }

    public void clear(UUID player) {
        targets.remove(player);
        reasons.remove(player);
        awaitingDetails.remove(player);
        started.remove(player);
    }

    public void clearAll() {
        targets.clear();
        reasons.clear();
        awaitingDetails.clear();
        started.clear();
    }
}
