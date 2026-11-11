package com.exenova.link.chat.pm;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for private messaging: reply targets, per-player ignore
 * lists, and social-spy subscribers. Pure logic (no Bukkit), so it is fully
 * unit-tested. State is per-session and reset on restart, which is the usual
 * behaviour for reply targets and social spy; ignore lists are intentionally
 * not persisted to keep this self-contained.
 */
public final class PrivateMessageService {
    private final Map<UUID, UUID> lastConversed = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> ignores = new ConcurrentHashMap<>();
    private final Set<UUID> socialSpy = ConcurrentHashMap.newKeySet();

    /** Records that {@code a} and {@code b} just exchanged a message, so /reply works both ways. */
    public void recordConversation(UUID a, UUID b) {
        lastConversed.put(a, b);
        lastConversed.put(b, a);
    }

    /** The player {@code who} should reply to, if any. */
    public UUID replyTarget(UUID who) { return lastConversed.get(who); }

    /** Adds {@code target} to {@code who}'s ignore list; returns false if already ignored. */
    public boolean ignore(UUID who, UUID target) {
        if (who.equals(target)) return false;
        return ignores.computeIfAbsent(who, k -> ConcurrentHashMap.newKeySet()).add(target);
    }

    /** Removes {@code target} from {@code who}'s ignore list; returns false if it wasn't ignored. */
    public boolean unignore(UUID who, UUID target) {
        Set<UUID> set = ignores.get(who);
        return set != null && set.remove(target);
    }

    public boolean isIgnoring(UUID who, UUID target) {
        Set<UUID> set = ignores.get(who);
        return set != null && set.contains(target);
    }

    /** Toggles social spy for {@code who}; returns the new state. */
    public boolean toggleSocialSpy(UUID who) {
        if (socialSpy.remove(who)) return false;
        socialSpy.add(who);
        return true;
    }

    public boolean isSpying(UUID who) { return socialSpy.contains(who); }
    public Set<UUID> spies() { return Set.copyOf(socialSpy); }

    /** Clears a player's transient state on quit (keeps who ignores them intact). */
    public void clear(UUID who) {
        lastConversed.remove(who);
        socialSpy.remove(who);
        ignores.remove(who);
        lastConversed.values().remove(who);
    }
}
