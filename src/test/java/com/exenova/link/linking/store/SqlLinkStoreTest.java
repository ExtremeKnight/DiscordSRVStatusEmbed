package com.exenova.link.linking.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqlLinkStoreTest {
    private static final String DISCORD_A = "123456789012345678";
    private static final String DISCORD_B = "876543210987654321";
    private final Logger logger = Logger.getLogger("test");

    @TempDir Path dir;
    private SqlLinkStore store;

    @BeforeEach
    void open() throws Exception {
        store = SqlLinkStore.sqlite(new File(dir.toFile(), "links.db"), "exenova_");
    }

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    @Test
    void putThenLoadRoundTrips() throws Exception {
        UUID player = UUID.randomUUID();
        store.put(player, DISCORD_A, 1000L, "user");
        assertEquals(Map.of(player, DISCORD_A), store.loadAll());
    }

    @Test
    void upsertReplacesExistingMinecraftRow() throws Exception {
        UUID player = UUID.randomUUID();
        store.put(player, DISCORD_A, 1000L, "user");
        store.put(player, DISCORD_B, 2000L, "user");
        assertEquals(Map.of(player, DISCORD_B), store.loadAll());
    }

    @Test
    void putClearsPreviousOwnerOfDiscordId() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.put(first, DISCORD_A, 1000L, "user");
        store.put(second, DISCORD_A, 2000L, "user"); // same Discord ID moves to a new account
        assertEquals(Map.of(second, DISCORD_A), store.loadAll());
    }

    @Test
    void removeDeletesTheRow() throws Exception {
        UUID player = UUID.randomUUID();
        store.put(player, DISCORD_A, 1000L, "user");
        store.remove(player);
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void dataSurvivesReopen() throws Exception {
        UUID player = UUID.randomUUID();
        store.put(player, DISCORD_A, 1000L, "user");
        store.close();
        store = SqlLinkStore.sqlite(new File(dir.toFile(), "links.db"), "exenova_");
        assertEquals(DISCORD_A, store.loadAll().get(player));
    }

    @Test
    void rejectsInvalidTablePrefix() {
        assertThrows(Exception.class, () -> SqlLinkStore.sqlite(new File(dir.toFile(), "x.db"), "bad prefix;"));
    }
}
