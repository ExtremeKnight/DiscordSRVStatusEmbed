package com.exenova.link.linking.store;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LinkStoreFactoryTest {
    private final Logger logger = Logger.getLogger("test");

    @TempDir Path dir;

    private MemoryConfiguration sqliteConfig() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("type", "sqlite");
        return config;
    }

    @Test
    void defaultsToSqlite() {
        LinkStore store = LinkStoreFactory.create(dir.toFile(), null, logger);
        assertEquals("SQLite", store.describe());
        store.close();
    }

    @Test
    void migratesLinksYamlIntoDatabaseThenRenamesIt() throws Exception {
        UUID player = UUID.randomUUID();
        String discord = "123456789012345678";
        File yaml = new File(dir.toFile(), "links.yml");
        Files.writeString(yaml.toPath(), "links:\n  " + player + ": \"" + discord + "\"\n");

        LinkStore store = LinkStoreFactory.create(dir.toFile(), sqliteConfig(), logger);
        try {
            assertEquals(Map.of(player, discord), store.loadAll(), "links should be imported into the DB");
        } finally {
            store.close();
        }
        assertFalse(yaml.isFile(), "links.yml should be renamed after migration");
        assertTrue(new File(dir.toFile(), "links.yml.migrated").isFile(), "a .migrated marker should remain");
    }

    @Test
    void doesNotReimportWhenDatabaseAlreadyHasData() throws Exception {
        // First run migrates one link and renames links.yml.
        UUID first = UUID.randomUUID();
        File yaml = new File(dir.toFile(), "links.yml");
        Files.writeString(yaml.toPath(), "links:\n  " + first + ": \"111111111111111111\"\n");
        LinkStoreFactory.create(dir.toFile(), sqliteConfig(), logger).close();

        // A stray new links.yml on a later start must not be re-imported over existing data.
        UUID second = UUID.randomUUID();
        Files.writeString(yaml.toPath(), "links:\n  " + second + ": \"222222222222222222\"\n");
        LinkStore store = LinkStoreFactory.create(dir.toFile(), sqliteConfig(), logger);
        try {
            Map<UUID, String> all = store.loadAll();
            assertEquals(1, all.size());
            assertTrue(all.containsKey(first));
            assertFalse(all.containsKey(second));
        } finally {
            store.close();
        }
    }
}
