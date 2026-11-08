package com.exenova.link.linking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LinkManagerTest {
    private static final String DISCORD = "123456789012345678";
    private static final String OTHER_DISCORD = "876543210987654321";
    private final Logger logger = Logger.getLogger("test");
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @TempDir Path dir;

    private LinkManager manager() { return new LinkManager(new File(dir.toFile(), "links.yml"), logger, now::get); }

    @Test
    void minecraftCodeRedeemedFromDiscordLinksBothWays() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        String code = links.createCodeForMinecraft(player);

        assertEquals(LinkManager.CODE_LENGTH, code.length());
        assertEquals(LinkManager.Result.LINKED, links.redeemFromDiscord(DISCORD, code.toLowerCase()));
        assertEquals(Optional.of(DISCORD), links.discordId(player));
        assertEquals(Optional.of(player), links.minecraftId(DISCORD));
    }

    @Test
    void codesAreSingleUseAndDirectional() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        String code = links.createCodeForMinecraft(player);

        // A Minecraft-issued code can't be redeemed in Minecraft.
        assertEquals(LinkManager.Result.INVALID_CODE, links.redeemFromMinecraft(UUID.randomUUID(), code));
        assertEquals(LinkManager.Result.LINKED, links.redeemFromDiscord(DISCORD, code));
        assertEquals(LinkManager.Result.INVALID_CODE, links.redeemFromDiscord(OTHER_DISCORD, code));
    }

    @Test
    void codesExpire() {
        LinkManager links = manager();
        String code = links.createCodeForDiscord(DISCORD);
        now.addAndGet(LinkManager.CODE_TTL_MS + 1);
        assertEquals(LinkManager.Result.INVALID_CODE, links.redeemFromMinecraft(UUID.randomUUID(), code));
    }

    @Test
    void failedAttemptsAreRateLimited() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        String real = links.createCodeForMinecraft(player);
        for (int i = 0; i < LinkManager.MAX_FAILED_ATTEMPTS; i++) {
            assertEquals(LinkManager.Result.INVALID_CODE, links.redeemFromDiscord(DISCORD, "ZZZZZZ"));
        }
        // Even the correct code is refused while rate limited...
        assertEquals(LinkManager.Result.RATE_LIMITED, links.redeemFromDiscord(DISCORD, real));
        // ...but other users are unaffected.
        assertEquals(LinkManager.Result.LINKED, links.redeemFromDiscord(OTHER_DISCORD, real));
    }

    @Test
    void relinkingReplacesBothSidesOfTheOldLink() {
        LinkManager links = manager();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        links.link(first, DISCORD);
        links.link(second, DISCORD);

        assertTrue(links.discordId(first).isEmpty());
        assertEquals(Optional.of(second), links.minecraftId(DISCORD));
        assertEquals(1, links.size());
    }

    @Test
    void linksPersistAcrossReload() {
        UUID player = UUID.randomUUID();
        manager().link(player, DISCORD);

        LinkManager reloaded = manager();
        reloaded.load();
        assertEquals(Optional.of(DISCORD), reloaded.discordId(player));
    }

    @Test
    void fallbackIsUsedOnlyWithoutLocalLink() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        links.setFallback(uuid -> Optional.of(OTHER_DISCORD), id -> Optional.empty());

        assertEquals(Optional.of(OTHER_DISCORD), links.discordId(player));
        links.link(player, DISCORD);
        assertEquals(Optional.of(DISCORD), links.discordId(player));
    }

    @Test
    void unlinkRemovesLink() {
        LinkManager links = manager();
        UUID player = UUID.randomUUID();
        links.link(player, DISCORD);
        assertEquals(Optional.of(player), links.unlinkDiscord(DISCORD));
        assertFalse(links.isLinked(player));
    }
}
