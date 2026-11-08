package com.exenova.link.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import com.exenova.link.ExeNovaLink;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;

/**
 * Exposes this plugin's live server/Discord status as PlaceholderAPI
 * placeholders (identifier "exenovalink", plus the legacy "discordstatusembed"), e.g. for use in a
 * scoreboard, tab list, or another plugin's messages:
 *
 * <pre>
 * %exenovalink_online%        -> "12"
 * %exenovalink_max%           -> "50"
 * %exenovalink_tps%           -> "19.87"
 * %exenovalink_uptime%        -> "3h 42m"
 * %exenovalink_discord_status%-> "READY" or "NOT READY"
 * %exenovalink_linked%         -> "true" / "false" for the viewing player
 * %exenovalink_discord_id%     -> the viewing player's linked Discord ID, or ""
 * %exenovalink_linked_count%   -> number of linked accounts
 * </pre>
 *
 * This class only extends PlaceholderExpansion, which only exists on the
 * classpath if PlaceholderAPI is installed; it is constructed and registered
 * from ExeNovaLink only after confirming PlaceholderAPI's plugin is present,
 * so this class is never loaded at all on a server without PlaceholderAPI.
 */
public final class DiscordStatusPlaceholders extends PlaceholderExpansion {
    private final ExeNovaLink plugin;
    private final String identifier;

    public DiscordStatusPlaceholders(ExeNovaLink plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
    }

    @Override
    public String getIdentifier() {
        return identifier;
    }

    @Override
    public String getAuthor() {
        return "ExeNova";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // Keep this expansion registered across `/papi reload`; the plugin
    // itself, not PlaceholderAPI, owns this expansion's lifecycle.
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(org.bukkit.OfflinePlayer player, String params) {
        if (params == null) return "";
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "online":
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "max":
                return String.valueOf(Bukkit.getMaxPlayers());
            case "tps":
                return formatTps(safeTps());
            case "uptime":
                return formatUptime(System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime());
            case "linked":
                return String.valueOf(player != null && plugin.links() != null && plugin.links().isLinked(player.getUniqueId()));
            case "discord_id":
                return player == null || plugin.links() == null ? "" : plugin.links().discordId(player.getUniqueId()).orElse("");
            case "linked_count":
                return String.valueOf(plugin.links() == null ? 0 : plugin.links().size());
            case "discord_status":
                return plugin.isDiscordReady() ? "READY" : "NOT READY";
            case "version":
                String configured = plugin.getConfig().getString("server-info.version", "");
                return (configured != null && !configured.isEmpty()) ? configured : Bukkit.getServer().getBukkitVersion();
            default:
                return null; // PlaceholderAPI convention: null means "unknown placeholder"
        }
    }

    private double safeTps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return (tps == null || tps.length == 0) ? 20.0 : tps[0];
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private String formatTps(double tps) {
        return String.format(java.util.Locale.ROOT, "%.2f", Math.min(tps, 20.0));
    }

    private String formatUptime(long millis) {
        long totalMinutes = millis / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours + "h " + minutes + "m";
    }
}
