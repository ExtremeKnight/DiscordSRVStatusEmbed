package com.exenova.link.bridge;

import java.util.Locale;

/**
 * Classifies a Discord attachment by file name / image flag so the bridge can
 * show a sensible clickable label in Minecraft chat ({@code [image: cat.png]},
 * {@code [gif]}, …). Pure and unit-tested; no rendering.
 */
public final class AttachmentPreview {
    public enum Kind {
        IMAGE("image"), GIF("gif"), VIDEO("video"), AUDIO("audio"), FILE("file");
        private final String label;
        Kind(String label) { this.label = label; }
        public String label() { return label; }
    }

    private AttachmentPreview() { }

    public static Kind kind(String fileName, boolean isImage) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".gif")) return Kind.GIF;
        if (isImage || name.matches(".*\\.(png|jpe?g|webp|bmp|tiff?)$")) return Kind.IMAGE;
        if (name.matches(".*\\.(mp4|mov|webm|mkv|avi)$")) return Kind.VIDEO;
        if (name.matches(".*\\.(mp3|ogg|wav|flac|m4a)$")) return Kind.AUDIO;
        return Kind.FILE;
    }

    /** Short chat label, e.g. "[image: cat.png]" (file name omitted for gifs). */
    public static String label(String fileName, boolean isImage) {
        Kind kind = kind(fileName, isImage);
        if (kind == Kind.GIF) return "[gif]";
        String name = fileName == null || fileName.isBlank() ? "" : ": " + fileName;
        return "[" + kind.label() + name + "]";
    }
}
