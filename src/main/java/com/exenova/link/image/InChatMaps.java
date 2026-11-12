package com.exenova.link.image;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.exenova.link.ExeNovaLink;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EXPERIMENTAL in-chat image/GIF display using ProtocolLib. When a player clicks
 * an image shared in the Discord bridge, this shows the image rendered onto a
 * map in their off-hand for a few seconds — without giving a real item and
 * without touching the main hand (the map item is sent client-side only).
 *
 * <p>Only constructed when ProtocolLib is installed. It leans on Bukkit's
 * {@link Player#sendMap(MapView)} for the pixels (so no hand-built MAP packet)
 * and uses ProtocolLib only for the client-side SET_SLOT. This path cannot be
 * verified in CI and should be smoke-tested on a live server.</p>
 *
 * <p>The raw-slot number for the off-hand in window 0, and the SET_SLOT field
 * order (windowId, stateId, slot, item), match modern Paper (1.17+). Older
 * servers may need adjustment.</p>
 */
public final class InChatMaps {
    private static final int OFFHAND_RAW_SLOT = 45;
    private static final int POOL_SIZE = 16;

    private record Pending(String url, long expires) { }

    private final ExeNovaLink plugin;
    private final ProtocolManager protocol;
    private final Deque<MapView> pool = new ArrayDeque<>();
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final AtomicLong tokenSeq = new AtomicLong();

    private InChatMaps(ExeNovaLink plugin) {
        this.plugin = plugin;
        this.protocol = ProtocolLibrary.getProtocolManager();
    }

    /** Builds an instance only if ProtocolLib is present; otherwise returns null. */
    public static InChatMaps createIfAvailable(ExeNovaLink plugin) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) return null;
        try {
            return new InChatMaps(plugin);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("ProtocolLib present but the in-chat map renderer could not start: " + throwable);
            return null;
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("images.in-chat.enabled", false);
    }

    /** Caches an image URL and returns a short token for a chat click to redeem. */
    public String cache(String url) {
        purge();
        String token = Long.toString(tokenSeq.incrementAndGet(), 36);
        tokens.put(token, new Pending(url, System.currentTimeMillis() + 10 * 60_000L));
        return token;
    }

    /** Redeems a token (from a chat click) and shows the image to {@code viewer}. */
    public void showFromToken(Player viewer, String token) {
        Pending pending = tokens.get(token);
        if (pending == null || pending.expires() < System.currentTimeMillis()) {
            viewer.sendMessage("§cThat image link has expired.");
            return;
        }
        UUID id = viewer.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] data = ImageDownloader.download(pending.url(), plugin.getConfig().getLong("images.max-bytes", 8_000_000L));
                int maxFrames = Math.max(1, plugin.getConfig().getInt("images.gif-max-frames", 40));
                int fps = plugin.getConfig().getInt("images.gif-fps", 10);
                List<BufferedImage> frames = ImageFrames.read(data, maxFrames);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) show(online, frames, fps);
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) online.sendMessage("§cCould not load that image: " + exception.getMessage());
                });
            }
        });
    }

    /** Shows the frames on a map in the viewer's off-hand for the configured duration (main thread). */
    public void show(Player viewer, List<BufferedImage> frames, int fps) {
        int seconds = Math.max(2, Math.min(60, plugin.getConfig().getInt("images.in-chat.duration-seconds", 12)));
        MapView view = borrowView(viewer);
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setScale(MapView.Scale.NORMAL);
        view.addRenderer(new MapImageRenderer(frames, fps));

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        meta.setMapView(view);
        mapItem.setItemMeta(meta);

        sendOffhand(viewer, mapItem);
        viewer.sendMap(view);
        viewer.sendMessage("§7Showing image in your off-hand for " + seconds + "s...");

        boolean animated = frames.size() > 1;
        UUID id = viewer.getUniqueId();
        final int[] task = {-1};
        if (animated) {
            task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null) online.sendMap(view);
            }, 2L, 2L).getTaskId();
        }
        int taskId = task[0];
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
            Player online = Bukkit.getPlayer(id);
            if (online != null) restoreOffhand(online);
        }, seconds * 20L);
    }

    private MapView borrowView(Player viewer) {
        if (pool.size() < POOL_SIZE) {
            MapView created = Bukkit.createMap(viewer.getWorld());
            pool.addLast(created);
            return created;
        }
        MapView reused = pool.pollFirst();
        pool.addLast(reused);
        return reused;
    }

    private void sendOffhand(Player player, ItemStack item) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SET_SLOT);
            packet.getIntegers().write(0, 0).write(1, 0).write(2, OFFHAND_RAW_SLOT);
            packet.getItemModifier().write(0, item);
            protocol.sendServerPacket(player, packet, false);
        } catch (Exception exception) {
            plugin.getLogger().warning("In-chat map: could not send the map item: " + exception.getMessage());
        }
    }

    private void restoreOffhand(Player player) {
        try {
            PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SET_SLOT);
            packet.getIntegers().write(0, 0).write(1, 0).write(2, OFFHAND_RAW_SLOT);
            packet.getItemModifier().write(0, player.getInventory().getItemInOffHand());
            protocol.sendServerPacket(player, packet, false);
        } catch (Exception exception) {
            player.updateInventory(); // fall back to a full resync
        }
    }

    private void purge() {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(pending -> pending.expires() < now);
    }
}
