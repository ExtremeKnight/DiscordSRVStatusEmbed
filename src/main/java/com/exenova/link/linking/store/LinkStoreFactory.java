package com.exenova.link.linking.store;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds the configured {@link LinkStore} and performs the one-time
 * {@code links.yml} → database migration. If the configured database can't be
 * opened, it falls back to the YAML store so linking keeps working rather than
 * taking the plugin down.
 */
public final class LinkStoreFactory {

    private LinkStoreFactory() { }

    /**
     * @param dataFolder the plugin data folder
     * @param storage    the {@code linking.storage} config section (may be null → SQLite defaults)
     * @param logger     plugin logger
     */
    public static LinkStore create(File dataFolder, ConfigurationSection storage, Logger logger) {
        File legacyYaml = new File(dataFolder, "links.yml");
        String type = storage == null ? "sqlite" : storage.getString("type", "sqlite").toLowerCase(java.util.Locale.ROOT);

        LinkStore store;
        try {
            store = switch (type) {
                case "mysql" -> mysql(storage, logger);
                case "sqlite" -> SqlLinkStore.sqlite(new File(dataFolder, "links.db"), tablePrefix(storage));
                default -> {
                    logger.warning("Unknown linking.storage.type '" + type + "'; using sqlite.");
                    yield SqlLinkStore.sqlite(new File(dataFolder, "links.db"), tablePrefix(storage));
                }
            };
        } catch (Throwable throwable) {
            logger.severe("Could not open the " + type + " link store (" + throwable.getMessage()
                    + "); falling back to links.yml. Links will still work but won't use the database.");
            return new YamlLinkStore(legacyYaml, logger);
        }

        migrateIfNeeded(store, legacyYaml, logger);
        return store;
    }

    private static SqlLinkStore mysql(ConfigurationSection storage, Logger logger) throws java.sql.SQLException {
        ConfigurationSection mysql = storage == null ? null : storage.getConfigurationSection("mysql");
        if (mysql == null) throw new java.sql.SQLException("linking.storage.mysql section is missing");
        return SqlLinkStore.mysql(
                mysql.getString("host", "localhost"),
                mysql.getInt("port", 3306),
                mysql.getString("database", "exenova"),
                mysql.getString("username", ""),
                mysql.getString("password", ""),
                mysql.getInt("pool-size", 6),
                mysql.getString("table-prefix", "exenova_"));
    }

    private static String tablePrefix(ConfigurationSection storage) {
        ConfigurationSection mysql = storage == null ? null : storage.getConfigurationSection("mysql");
        return mysql == null ? "exenova_" : mysql.getString("table-prefix", "exenova_");
    }

    /** Imports an existing links.yml into an empty DB once, then renames it. */
    private static void migrateIfNeeded(LinkStore store, File legacyYaml, Logger logger) {
        if (store instanceof YamlLinkStore || !legacyYaml.isFile()) return;
        try {
            if (!store.loadAll().isEmpty()) return; // DB already has data; don't re-import
            YamlLinkStore yaml = new YamlLinkStore(legacyYaml, logger);
            Map<UUID, String> existing = yaml.loadAll();
            if (existing.isEmpty()) { yaml.markMigrated(); return; }
            long now = System.currentTimeMillis();
            existing.forEach((uuid, discord) -> store.put(uuid, discord, now, "migrated"));
            yaml.markMigrated();
            logger.info("Migrated " + existing.size() + " link(s) from links.yml into " + store.describe() + ".");
        } catch (Exception exception) {
            logger.warning("Could not migrate links.yml into " + store.describe() + ": " + exception.getMessage()
                    + " (links.yml left in place).");
        }
    }
}
