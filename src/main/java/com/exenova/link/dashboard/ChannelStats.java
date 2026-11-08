package com.exenova.link.dashboard;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renames Discord channels to show live stats, e.g. a locked voice channel
 * named "👥 Online: 12/50". Discord allows only 2 renames per channel per 10
 * minutes, so the interval is clamped to 10 minutes and unchanged names are
 * skipped.
 */
public final class ChannelStats {
    private final ExeNovaLink plugin;
    private final Map<String, String> lastNames = new ConcurrentHashMap<>();

    public ChannelStats(ExeNovaLink plugin) { this.plugin = plugin; }

    public long intervalTicks() {
        return Math.max(10, plugin.getConfig().getLong("channel-stats.interval-minutes", 10)) * 60L * 20L;
    }

    public void update() {
        if (!plugin.getConfig().getBoolean("channel-stats.enabled", false)) return;
        JDA jda = plugin.jda();
        ConfigurationSection channels = plugin.getConfig().getConfigurationSection("channel-stats.channels");
        if (jda == null || channels == null) return;
        String online = String.valueOf(Bukkit.getOnlinePlayers().size());
        String max = String.valueOf(Bukkit.getMaxPlayers());
        String linked = String.valueOf(plugin.links().size());
        for (String id : channels.getKeys(false)) {
            GuildChannel channel = id.matches("\\d{17,20}") ? jda.getGuildChannelById(id) : null;
            if (channel == null) continue;
            String name = channels.getString(id, "").replace("{online}", online).replace("{max}", max).replace("{linked}", linked)
                    .replace("{status}", "Online");
            if (name.isBlank()) continue;
            if (name.length() > 100) name = name.substring(0, 100);
            if (name.equals(lastNames.get(id)) || name.equals(channel.getName())) continue;
            String finalName = name;
            channel.getManager().setName(name).queue(done -> lastNames.put(id, finalName),
                    failure -> plugin.getLogger().warning("Could not rename stats channel " + id + " (needs Manage Channels): " + failure.getMessage()));
        }
    }
}
