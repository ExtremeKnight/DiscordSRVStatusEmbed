package com.exenova.link.chat;

import com.exenova.link.ExeNovaLink;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Notifies players when they're {@code @mentioned} in chat: a sound and a
 * configurable message (optionally as a title). The matching logic lives in
 * {@link Mentions}; this only turns matches into notifications.
 */
public final class MentionListener implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ExeNovaLink plugin;

    public MentionListener(ExeNovaLink plugin) { this.plugin = plugin; }

    public void unregister() { HandlerList.unregisterAll(this); }

    private boolean enabled() { return plugin.getConfig().getBoolean("mentions.enabled", true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled()) return;
        Player sender = event.getPlayer();
        if (!sender.hasPermission("exenova.chat.mention")) return;
        List<String> names = Bukkit.getOnlinePlayers().stream()
                .filter(online -> !(sender instanceof Player s) || s.canSee(online))
                .map(Player::getName).toList();
        List<String> mentioned = Mentions.find(PLAIN.serialize(event.message()), names);
        if (mentioned.isEmpty()) return;

        String message = plugin.getConfig().getString("mentions.message", "&e{player} mentioned you in chat");
        boolean title = plugin.getConfig().getBoolean("mentions.title", false);
        Sound sound = sound(plugin.getConfig().getString("mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        for (String name : mentioned) {
            Player target = Bukkit.getPlayerExact(name);
            if (target == null || target.equals(sender)) continue;
            Component notice = LEGACY.deserialize(message.replace("{player}", sender.getName()));
            // Bukkit calls that aren't guaranteed main-thread-safe go back to the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!target.isOnline()) return;
                if (sound != null) target.playSound(target.getLocation(), sound, 1f, 1.2f);
                if (title) target.showTitle(net.kyori.adventure.title.Title.title(Component.empty(), notice));
                else target.sendMessage(notice);
            });
        }
    }

    @SuppressWarnings("deprecation")
    private static Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        try { return Sound.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
