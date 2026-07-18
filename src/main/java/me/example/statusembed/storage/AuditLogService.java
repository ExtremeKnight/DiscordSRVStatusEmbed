package me.example.statusembed.storage;

import me.example.statusembed.StatusEmbed;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** File-backed audit logger with asynchronous writes and bounded rotation. */
public final class AuditLogService {
    private final StatusEmbed plugin;

    public AuditLogService(StatusEmbed plugin) { this.plugin = plugin; }

    public void write(String entry) {
        if (!plugin.getConfig().getBoolean("audit-log.enabled", true)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File log = new File(plugin.getDataFolder(), "audit.log");
                rotateIfNeeded(log);
                Files.writeString(log.toPath(), Instant.now() + " " + entry + System.lineSeparator(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not write audit log: " + exception.getMessage());
            }
        });
    }

    private void rotateIfNeeded(File log) throws IOException {
        long maxBytes = Math.max(1024L, plugin.getConfig().getLong("audit-log.max-bytes", 5_000_000L));
        if (!log.isFile() || log.length() < maxBytes) return;
        File rotated = new File(plugin.getDataFolder(), "audit-" + System.currentTimeMillis() + ".log");
        Files.move(log.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
