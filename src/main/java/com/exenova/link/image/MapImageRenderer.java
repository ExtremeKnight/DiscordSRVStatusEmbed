package com.exenova.link.image;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Paints one or more pre-converted frames onto a Minecraft map. For a single
 * image it paints once; for an animated GIF it advances frames on wall-clock
 * time (capped fps) and repaints only when the frame changes, so it doesn't
 * re-send map data every tick.
 */
public final class MapImageRenderer extends MapRenderer {
    @SuppressWarnings("deprecation") // MapPalette#imageToBytes is the supported way to fill a map
    private static byte[] toMapColors(BufferedImage frame) { return MapPalette.imageToBytes(frame); }

    private final byte[][] frames;
    private final long frameMillis;
    private final long start = System.currentTimeMillis();
    // Last frame painted for each viewer, so we repaint only on change and every
    // viewer still gets their own canvas filled (the canvas is per-player).
    private final java.util.Map<java.util.UUID, Integer> paintedByViewer = new java.util.concurrent.ConcurrentHashMap<>();

    public MapImageRenderer(List<BufferedImage> images, int fps) {
        this.frames = new byte[images.size()][];
        for (int i = 0; i < images.size(); i++) this.frames[i] = toMapColors(images.get(i));
        int safeFps = Math.max(1, Math.min(20, fps));
        this.frameMillis = Math.max(1, 1000L / safeFps);
    }

    public int frameCount() { return frames.length; }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        int index = frames.length == 1 ? 0 : (int) ((System.currentTimeMillis() - start) / frameMillis % frames.length);
        Integer painted = paintedByViewer.get(player.getUniqueId());
        if (painted != null && painted == index) return;
        paintedByViewer.put(player.getUniqueId(), index);
        byte[] colors = frames[index];
        for (int x = 0; x < ImageFrames.MAP_SIZE; x++) {
            for (int y = 0; y < ImageFrames.MAP_SIZE; y++) {
                canvas.setPixel(x, y, colors[y * ImageFrames.MAP_SIZE + x]);
            }
        }
    }
}
