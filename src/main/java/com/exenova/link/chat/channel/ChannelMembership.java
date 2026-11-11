package com.exenova.link.chat.channel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which channel each player is currently talking in ("focus"). Pure,
 * in-memory and unit-tested. Membership beyond focus (joining several channels
 * to receive them) is intentionally out of scope for this first cut — everyone
 * receives every global channel they can use; focus only decides where a
 * player's own messages go.
 */
public final class ChannelMembership {
    private final Map<UUID, String> focus = new ConcurrentHashMap<>();

    /** Sets the player's active channel (stores the canonical name). */
    public void setFocus(UUID player, Channel channel) { focus.put(player, channel.name()); }

    /** The player's active channel name, or null if they haven't chosen one. */
    public String focus(UUID player) { return focus.get(player); }

    /** Resolves the player's active channel, falling back to the registry default. */
    public Channel active(UUID player, ChannelRegistry registry) {
        String name = focus.get(player);
        return registry.byExactName(name).orElseGet(registry::defaultChannel);
    }

    public void clear(UUID player) { focus.remove(player); }
}
