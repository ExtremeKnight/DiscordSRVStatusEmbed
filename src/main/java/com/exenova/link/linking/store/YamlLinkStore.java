package com.exenova.link.linking.store;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The original {@code links.yml} backend. Kept so existing servers can be read
 * for a one-time migration, and as an emergency fallback when the configured
 * database cannot be opened. Writes rewrite the whole file atomically
 * (temp file + move), as the previous {@code LinkManager} did.
 */
public final class YamlLinkStore implements LinkStore {
    private final File file;
    private final Logger logger;
    private final Object lock = new Object();
    private final Map<UUID, String> cache = new LinkedHashMap<>();

    public YamlLinkStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public boolean exists() { return file.isFile(); }

    @Override
    public Map<UUID, String> loadAll() {
        synchronized (lock) {
            cache.clear();
            if (!file.isFile()) return new LinkedHashMap<>();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            var section = yaml.getConfigurationSection("links");
            if (section != null) for (String key : section.getKeys(false)) {
                String discordId = section.getString(key, "");
                try {
                    if (discordId.matches("\\d{17,20}")) cache.put(UUID.fromString(key), discordId);
                } catch (IllegalArgumentException ignored) {
                    logger.warning("Skipping invalid entry in " + file.getName() + ": " + key);
                }
            }
            return new LinkedHashMap<>(cache);
        }
    }

    @Override
    public void put(UUID minecraft, String discordId, long linkedAt, String source) {
        synchronized (lock) {
            cache.values().removeIf(discordId::equals); // one Discord ID owns one account
            cache.put(minecraft, discordId);
            write();
        }
    }

    @Override
    public void remove(UUID minecraft) {
        synchronized (lock) {
            if (cache.remove(minecraft) != null) write();
        }
    }

    private void write() {
        YamlConfiguration yaml = new YamlConfiguration();
        cache.forEach((uuid, discord) -> yaml.set("links." + uuid, discord));
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Could not create " + parent);
            File temp = new File(parent, file.getName() + ".tmp");
            yaml.save(temp);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.warning("Could not save " + file.getName() + ": " + exception.getMessage());
        }
    }

    /** Renames the backing file after a successful migration so it is only imported once. */
    public void markMigrated() {
        synchronized (lock) {
            if (!file.isFile()) return;
            File done = new File(file.getParentFile(), file.getName() + ".migrated");
            try {
                Files.move(file.toPath(), done.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                logger.warning("Links migrated, but " + file.getName() + " could not be renamed: " + exception.getMessage());
            }
        }
    }

    @Override
    public String describe() { return file.getName(); }
}
