package com.exenova.link.chat.channel;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per player, which channel they are talking in ("focus") and which
 * channels they receive ("subscriptions"). Pure, in-memory and unit-tested.
 *
 * <p>A player receives a channel only if subscribed to it; focusing a channel
 * also subscribes. On join, {@link #joinDefaults} subscribes the player to every
 * auto-join channel they may use. This is what makes per-channel Discord
 * bridging well-defined: an inbound Discord message for a channel is delivered
 * to exactly the online subscribers of that channel.</p>
 */
public final class ChannelMembership {
    private final Map<UUID, String> focus = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> subscriptions = new ConcurrentHashMap<>();

    /** Sets the player's active channel and subscribes them to it. */
    public void setFocus(UUID player, Channel channel) {
        focus.put(player, channel.name());
        subscribe(player, channel);
    }

    public String focus(UUID player) { return focus.get(player); }

    /** Resolves the player's active channel, falling back to the registry default. */
    public Channel active(UUID player, ChannelRegistry registry) {
        return registry.byExactName(focus.get(player)).orElseGet(registry::defaultChannel);
    }

    public void subscribe(UUID player, Channel channel) {
        subscriptions.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(channel.name());
    }

    /** Unsubscribes; returns false if they weren't subscribed. */
    public boolean unsubscribe(UUID player, Channel channel) {
        Set<String> set = subscriptions.get(player);
        return set != null && set.remove(channel.name());
    }

    public boolean isSubscribed(UUID player, Channel channel) {
        Set<String> set = subscriptions.get(player);
        return set != null && set.contains(channel.name());
    }

    /** Subscribes to every auto-join channel the player may use, and focuses the default if usable. */
    public void joinDefaults(UUID player, ChannelRegistry registry, org.bukkit.permissions.Permissible who) {
        for (Channel channel : registry.all()) {
            if (channel.autoJoin() && channel.usableBy(who)) subscribe(player, channel);
        }
        Channel def = registry.defaultChannel();
        if (def.usableBy(who)) setFocus(player, def);
    }

    public void clear(UUID player) {
        focus.remove(player);
        subscriptions.remove(player);
    }
}
