package com.exenova.link.image;

import com.exenova.link.ExeNovaLink;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Turns an image URL into a filled map a player receives. This is the Bukkit-API
 * display path: it hands the viewer a real map item. Showing an image in chat to
 * everyone without an item needs client-bound map packets (ProtocolLib/NMS) and
 * is a separate, version-sensitive follow-up — see the roadmap.
 */
public final class ImageMaps {
    private final ExeNovaLink plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ImageMaps(ExeNovaLink plugin) { this.plugin = plugin; }

    public boolean enabled() { return plugin.getConfig().getBoolean("images.enabled", false); }

    /** Downloads the image off-thread, builds the map on the main thread, and gives it to {@code player}. */
    public void giveFromUrl(Player player, String url) {
        if (!enabled()) { player.sendMessage("§cImage maps are disabled."); return; }
        if (!url.matches("(?i)https?://\\S+")) { player.sendMessage("§cThat doesn't look like a URL."); return; }
        java.util.UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                byte[] data = download(url);
                int maxFrames = Math.max(1, plugin.getConfig().getInt("images.gif-max-frames", 40));
                int fps = plugin.getConfig().getInt("images.gif-fps", 10);
                List<BufferedImage> frames = ImageFrames.read(data, maxFrames);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online == null) return;
                    online.getInventory().addItem(buildMap(online, frames, fps));
                    online.sendMessage("§aHere's your image map (" + frames.size() + " frame(s)).");
                });
            } catch (Exception exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null) online.sendMessage("§cCould not load that image: " + exception.getMessage());
                });
            }
        });
    }

    private ItemStack buildMap(Player player, List<BufferedImage> frames, int fps) {
        MapView view = Bukkit.createMap(player.getWorld());
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setScale(MapView.Scale.NORMAL);
        view.addRenderer(new MapImageRenderer(frames, fps));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        return item;
    }

    private byte[] download(String url) throws Exception {
        long maxBytes = Math.max(1, plugin.getConfig().getLong("images.max-bytes", 8_000_000L));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ExeNovaLink").GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new Exception("HTTP " + response.statusCode());
        byte[] body = response.body();
        if (body.length > maxBytes) throw new Exception("image too large (" + body.length + " bytes)");
        return body;
    }
}
