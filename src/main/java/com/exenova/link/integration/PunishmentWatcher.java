package com.exenova.link.integration;

import com.exenova.link.ExeNovaLink;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;

/**
 * Plugin-agnostic punishment log: watches staff running punishment commands
 * (/ban, /tempban, /mute, /kick, /warn ... from LiteBans, EssentialsX, CMI,
 * vanilla or anything else) and posts them to Discord. Only commands the
 * sender actually has permission for are logged. AdvancedBan is covered more
 * precisely by its own event (see Integrations), so this stays quiet for it.
 */
public final class PunishmentWatcher implements Listener {
    private final ExeNovaLink plugin;

    public PunishmentWatcher(ExeNovaLink plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) { handle(event.getPlayer(), event.getMessage().substring(1)); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) { handle(event.getSender(), event.getCommand()); }

    private void handle(CommandSender sender, String line) {
        if (!plugin.getConfig().getBoolean("punishments.enabled", false)) return;
        if (plugin.integrations().activeNames().contains("AdvancedBan")) return;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 2) return; // needs at least a target
        String label = parts[0].toLowerCase(Locale.ROOT);
        String root = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        if (plugin.getConfig().getStringList("punishments.watch-commands").stream().noneMatch(root::equalsIgnoreCase)) return;
        Command command = Bukkit.getCommandMap().getCommand(label);
        if (command == null || !command.testPermissionSilent(sender)) return;
        String reason = parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 2, parts.length)) : "—";
        plugin.postToChannel("punishments.channel-id", new EmbedBuilder()
                .setTitle("🔨 /" + root)
                .addField("Player", MarkdownSanitizer.escape(parts[1]), true)
                .addField("Staff", MarkdownSanitizer.escape(sender.getName()), true)
                .addField("Details", MarkdownSanitizer.escape(reason.length() > 1000 ? reason.substring(0, 1000) : reason), false)
                .setColor(root.startsWith("un") || root.equals("pardon") ? new Color(0x57F287) : new Color(0xED4245))
                .setTimestamp(Instant.now()).build());
    }
}
