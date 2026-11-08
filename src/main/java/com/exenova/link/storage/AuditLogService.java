package com.exenova.link.storage;

import com.exenova.link.ExeNovaLink;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** File-backed audit logger with asynchronous writes and bounded rotation. */
public final class AuditLogService {
    private final ExeNovaLink plugin;
    /** Serializes writes from concurrent async tasks so lines never interleave and rotation never races an append. */
    private final Object lock = new Object();

    public AuditLogService(ExeNovaLink plugin) { this.plugin = plugin; }

    public void write(String entry) {
        if (!plugin.getConfig().getBoolean("audit-log.enabled", true)) return;
        // Entries contain user-supplied names/text; flatten newlines so one
        // event can never forge extra audit lines.
        String line = Instant.now() + " " + entry.replaceAll("[\\r\\n]+", " ") + System.lineSeparator();
        if (!plugin.isEnabled()) { append(line); return; }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> append(line));
    }

    private void append(String line) {
        synchronized (lock) {
            try {
                File log = new File(plugin.getDataFolder(), "audit.log");
                rotateIfNeeded(log);
                Files.writeString(log.toPath(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write audit log: " + exception.getMessage());
            }
        }
    }

    private void rotateIfNeeded(File log) throws IOException {
        long maxBytes = Math.max(1024L, plugin.getConfig().getLong("audit-log.max-bytes", 5_000_000L));
        if (!log.isFile() || log.length() < maxBytes) return;
        File rotated = new File(plugin.getDataFolder(), "audit-" + System.currentTimeMillis() + ".log");
        Files.move(log.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
