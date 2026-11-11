package com.exenova.link.image;

import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageFramesTest {
    private static byte[] png(int w, int h, Color color) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void readsSinglePngAsOne128Frame() throws Exception {
        List<BufferedImage> frames = ImageFrames.read(png(64, 64, Color.RED), 20);
        assertEquals(1, frames.size());
        assertEquals(128, frames.get(0).getWidth());
        assertEquals(128, frames.get(0).getHeight());
    }

    @Test
    void scalePreservesAspectRatioOnA128Canvas() {
        BufferedImage wide = new BufferedImage(256, 64, BufferedImage.TYPE_INT_ARGB);
        BufferedImage scaled = ImageFrames.scale(wide);
        assertEquals(128, scaled.getWidth());
        assertEquals(128, scaled.getHeight());
        // A 4:1 image fits to 128x32 centred; the top-left corner is transparent letterbox.
        assertEquals(0, (scaled.getRGB(0, 0) >>> 24), "letterbox is transparent");
    }

    @Test
    void readsAnimatedGifFrames() throws Exception {
        byte[] gif = threeFrameGif();
        List<BufferedImage> frames = ImageFrames.read(gif, 20);
        assertEquals(3, frames.size());
        frames.forEach(f -> { assertEquals(128, f.getWidth()); assertEquals(128, f.getHeight()); });
    }

    @Test
    void maxFramesIsRespected() throws Exception {
        assertEquals(2, ImageFrames.read(threeFrameGif(), 2).size());
    }

    @Test
    void invalidDataThrows() {
        assertThrows(Exception.class, () -> ImageFrames.read("not an image".getBytes(), 20));
    }

    private static byte[] threeFrameGif() throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
            for (Color color : colors) {
                BufferedImage frame = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(color);
                g.fillRect(0, 0, 32, 32);
                g.dispose();
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata metadata = writer.getDefaultImageMetadata(
                        javax.imageio.ImageTypeSpecifier.createFromRenderedImage(frame), param);
                writer.writeToSequence(new IIOImage(frame, null, metadata), param);
            }
            writer.endWriteSequence();
        }
        writer.dispose();
        return bytes.toByteArray();
    }
}
