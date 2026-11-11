package com.exenova.link.integration;

import com.exenova.link.ExeNovaLink;
import github.scarsz.discordsrv.DiscordSRV;

import java.util.UUID;

/**
 * Optional DiscordSRV integration. ExeNovaLink runs its own bot, but servers
 * migrating from DiscordSRV can keep their existing account links: when a
 * player has no ExeNovaLink link we fall back to DiscordSRV's.
 */
final class DiscordSrvHook {
    private final ExeNovaLink plugin;

    DiscordSrvHook(ExeNovaLink plugin) {
        this.plugin = plugin;
        if (chatBridgeConfigured() && plugin.getConfig().getBoolean("bridge.enabled", false)) {
            plugin.getLogger().warning("Both DiscordSRV and ExeNovaLink's chat bridge are enabled; chat may be relayed twice. "
                    + "Disable one of them (bridge.enabled in ExeNovaLink's config.yml).");
        }
    }

    String discordId(UUID uuid) {
        if (!plugin.getConfig().getBoolean("integrations.discordsrv.use-account-links", true)) return null;
        try { return DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(uuid); }
        catch (RuntimeException | LinkageError error) { return null; }
    }

    UUID uuid(String discordId) {
        if (!plugin.getConfig().getBoolean("integrations.discordsrv.use-account-links", true)) return null;
        try { return DiscordSRV.getPlugin().getAccountLinkManager().getUuid(discordId); }
        catch (RuntimeException | LinkageError error) { return null; }
    }

    boolean chatBridgeConfigured() {
        try { return !DiscordSRV.getPlugin().getChannels().isEmpty(); }
        catch (RuntimeException | LinkageError error) { return false; }
    }
}
