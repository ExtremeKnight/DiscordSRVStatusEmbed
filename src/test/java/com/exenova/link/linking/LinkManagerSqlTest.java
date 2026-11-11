package com.exenova.link.linking;

import com.exenova.link.linking.store.SqlLinkStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs LinkManager's core behaviour against the SQLite store, proving the store integration is correct. */
class LinkManagerSqlTest {
    private static final String DISCORD = "123456789012345678";
    private final Logger logger = Logger.getLogger("test");

    @TempDir Path dir;
    private SqlLinkStore store;

    private LinkManager manager() { return new LinkManager(store, logger); }

    @BeforeEach
    void open() throws Exception {
        store = SqlLinkStore.sqlite(new File(dir.toFile(), "links.db"), "exenova_");
    }

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    @Test
    void linkPersistsAndReloads() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        assertEquals(LinkManager.Result.LINKED, links.link(player, DISCORD));

        LinkManager reloaded = manager();
        reloaded.load();
        assertEquals(Optional.of(DISCORD), reloaded.discordId(player));
        assertEquals(Optional.of(player), reloaded.minecraftId(DISCORD));
    }

    @Test
    void relinkingReplacesBothSidesInTheDatabase() {
        LinkManager links = manager();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        links.link(first, DISCORD);
        links.link(second, DISCORD);

        LinkManager reloaded = manager();
        reloaded.load();
        assertTrue(reloaded.discordId(first).isEmpty());
        assertEquals(Optional.of(second), reloaded.minecraftId(DISCORD));
        assertEquals(1, reloaded.size());
    }

    @Test
    void unlinkRemovesFromDatabase() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        links.link(player, DISCORD);
        assertEquals(Optional.of(DISCORD), links.unlinkMinecraft(player));

        LinkManager reloaded = manager();
        reloaded.load();
        assertFalse(reloaded.isLinked(player));
        assertEquals(0, reloaded.size());
    }

    @Test
    void redeemFromDiscordLinksAndPersists() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        String code = links.createCodeForMinecraft(player);
        assertEquals(LinkManager.Result.LINKED, links.redeemFromDiscord(DISCORD, code));

        LinkManager reloaded = manager();
        reloaded.load();
        assertEquals(Optional.of(DISCORD), reloaded.discordId(player));
    }
}
