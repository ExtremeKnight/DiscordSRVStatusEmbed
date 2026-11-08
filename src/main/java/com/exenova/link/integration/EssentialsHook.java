package com.exenova.link.integration;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * EssentialsX mute / AFK / vanish state via reflection on its public
 * {@code User} API (getUser(Player).isMuted() etc.), so no EssentialsX
 * repository is needed at build time and API drift degrades to "not muted".
 */
final class EssentialsHook {
    private final Plugin essentials;

    EssentialsHook(Plugin essentials) { this.essentials = essentials; }

    private Object user(Player player) { return Integrations.call(essentials, "getUser", player); }

    boolean isMuted(Player player) { return Boolean.TRUE.equals(Integrations.call(user(player), "isMuted")); }
    boolean isAfk(Player player) { return Boolean.TRUE.equals(Integrations.call(user(player), "isAfk")); }
    boolean isVanished(Player player) { return Boolean.TRUE.equals(Integrations.call(user(player), "isVanished")); }
}
