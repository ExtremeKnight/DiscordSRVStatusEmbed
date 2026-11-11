package com.exenova.link.chat.channel;

import com.exenova.link.ExeNovaLink;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Coordinator for in-game chat channels: owns the {@link ChannelRegistry} and
 * {@link ChannelMembership}, handles {@code /channel}, and answers the two
 * questions the {@link com.exenova.link.chat.ChatFormatter} asks per message —
 * which format to use, and (for local channels) who should receive it.
 *
 * <p>Enabled by {@code chat.channels.enabled} (default off). When off, the
 * formatter uses its own group-formats and every message is global.</p>
 */
public final class ChatChannels implements Listener {
    private final ExeNovaLink plugin;
    private final ChannelMembership membership = new ChannelMembership();
    private volatile ChannelRegistry registry;

    public ChatChannels(ExeNovaLink plugin) {
        this.plugin = plugin;
        reload();
    }

    public boolean enabled() { return plugin.getConfig().getBoolean("chat.channels.enabled", false); }

    public void reload() {
        registry = ChannelRegistry.fromConfig(plugin.getConfig().getConfigurationSection("chat"));
    }

    public void unregister() { HandlerList.unregisterAll(this); }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { membership.clear(event.getPlayer().getUniqueId()); }

    /** The channel a player is currently talking in (never null when enabled). */
    public Channel active(Player player) { return membership.active(player.getUniqueId(), registry); }

    /** Removes viewers who shouldn't receive {@code player}'s message in {@code channel} (local radius). */
    public void filterRecipients(Player player, Channel channel, java.util.Set<net.kyori.adventure.audience.Audience> viewers) {
        if (!channel.isLocal()) return;
        Location origin = player.getLocation();
        long radiusSq = (long) channel.radius() * channel.radius();
        viewers.removeIf(viewer -> {
            if (!(viewer instanceof Player other)) return !player.hasPermission("exenova.chat.channel." + channel.name());
            if (other.equals(player)) return false;
            if (!other.getWorld().equals(origin.getWorld())) return true;
            return other.getLocation().distanceSquared(origin) > radiusSq;
        });
    }

    public boolean onCommand(CommandSender sender, String[] args) {
        if (!enabled()) { sender.sendMessage("§cChannels are not enabled."); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            String list = registry.usableBy(player).stream().map(Channel::name).collect(Collectors.joining(", "));
            sender.sendMessage("§6Channels: §f" + (list.isEmpty() ? "none" : list));
            sender.sendMessage("§7Talking in: §f" + active(player).name());
            return true;
        }
        Channel channel = registry.resolve(args[0]).orElse(null);
        if (channel == null) { sender.sendMessage("§cUnknown channel: " + args[0]); return true; }
        if (!channel.usableBy(player)) { sender.sendMessage("§cYou don't have access to that channel."); return true; }
        membership.setFocus(player.getUniqueId(), channel);
        sender.sendMessage("§aNow talking in §f" + channel.name() + "§a.");
        return true;
    }

    public List<String> complete(CommandSender sender, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<Channel> usable = sender instanceof Player p ? registry.usableBy(p) : registry.all();
        return usable.stream().map(Channel::name).filter(name -> name.startsWith(lower)).collect(Collectors.toList());
    }

    public ChannelRegistry registry() { return registry; }
    public void clear(UUID player) { membership.clear(player); }
}
