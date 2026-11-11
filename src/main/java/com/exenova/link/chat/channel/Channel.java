package com.exenova.link.chat.channel;

import java.util.List;
import java.util.Locale;

/**
 * An immutable chat channel definition loaded from config.
 *
 * @param name     canonical lower-case id
 * @param aliases  alternative names (lower-case)
 * @param format   MiniMessage format (see ChatFormatter); {player}/{message} supported
 * @param radius   delivery radius in blocks; {@code <= 0} means global (whole server)
 * @param permission permission needed to use the channel, or empty for everyone
 * @param discordChannelId optional Discord channel ID to bridge this channel to (empty = none)
 * @param autoJoin whether players join (and can see) this channel automatically
 */
public record Channel(String name, List<String> aliases, String format, int radius,
                      String permission, String discordChannelId, boolean autoJoin) {
    public Channel {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("channel name is required");
        name = name.toLowerCase(Locale.ROOT);
        aliases = aliases == null ? List.of() : aliases.stream().map(a -> a.toLowerCase(Locale.ROOT)).toList();
        format = format == null || format.isBlank() ? "<gray>{player}</gray> <dark_gray>»</dark_gray> <white>{message}</white>" : format;
        permission = permission == null ? "" : permission;
        discordChannelId = discordChannelId == null ? "" : discordChannelId;
    }

    public boolean isLocal() { return radius > 0; }

    public boolean matches(String query) {
        if (query == null) return false;
        String q = query.toLowerCase(Locale.ROOT);
        return name.equals(q) || aliases.contains(q);
    }

    public boolean usableBy(org.bukkit.permissions.Permissible who) {
        return permission.isEmpty() || who.hasPermission(permission);
    }
}
