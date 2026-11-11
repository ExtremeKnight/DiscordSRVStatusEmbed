package com.exenova.link.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Decodes image bytes (PNG/JPG/GIF) into one or more 128×128 frames ready to be
 * painted onto a Minecraft map. Animated GIFs yield one frame per GIF frame,
 * composited so partial frames build on the previous one. Pure (java.awt +
 * ImageIO only), so it is unit-tested without a server.
 */
public final class ImageFrames {
    public static final int MAP_SIZE = 128;

    private ImageFrames() { }

    /** Reads up to {@code maxFrames} frames, each scaled to 128×128. Never returns empty on valid image data. */
    public static List<BufferedImage> read(byte[] data, int maxFrames) throws IOException {
        List<BufferedImage> raw = readRaw(data, Math.max(1, maxFrames));
        List<BufferedImage> scaled = new ArrayList<>(raw.size());
        for (BufferedImage frame : raw) scaled.add(scale(frame));
        if (scaled.isEmpty()) throw new IOException("No decodable image frames");
        return scaled;
    }

    private static List<BufferedImage> readRaw(byte[] data, int maxFrames) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) throw new IOException("Unreadable image data");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No image reader for this data");
            ImageReader reader = readers.next();
            reader.setInput(input);
            int count;
            try { count = reader.getNumImages(true); } catch (IOException ignored) { count = 1; }
            if (count <= 0) count = 1;
            count = Math.min(count, maxFrames);

            List<BufferedImage> frames = new ArrayList<>(count);
            BufferedImage canvas = null;
            for (int i = 0; i < count; i++) {
                BufferedImage frame;
                try { frame = reader.read(i); } catch (IndexOutOfBoundsException | IOException stop) { break; }
                if (canvas == null) {
                    canvas = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                }
                // Composite onto the running canvas so partial GIF frames look right.
                Graphics2D g = canvas.createGraphics();
                g.drawImage(frame, 0, 0, null);
                g.dispose();
                BufferedImage copy = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
                copy.createGraphics().drawImage(canvas, 0, 0, null);
                frames.add(copy);
            }
            reader.dispose();
            if (frames.isEmpty()) throw new IOException("No frames decoded");
            return frames;
        }
    }

    /** Scales {@code src} to fit a 128×128 canvas, preserving aspect ratio and centring (transparent letterbox). */
    public static BufferedImage scale(BufferedImage src) {
        BufferedImage out = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        double ratio = Math.min((double) MAP_SIZE / src.getWidth(), (double) MAP_SIZE / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(src.getHeight() * ratio));
        int x = (MAP_SIZE - w) / 2;
        int y = (MAP_SIZE - h) / 2;
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, x, y, w, h, null);
        g.dispose();
        return out;
    }
}
