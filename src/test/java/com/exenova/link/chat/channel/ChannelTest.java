package com.exenova.link.chat.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChannelTest {
    private ChannelRegistry registry() {
        return ChannelRegistry.fromMap(List.of(
                new Channel("global", List.of("g"), "<white>{message}", 0, "", "", true),
                new Channel("staff", List.of("sc", "a"), "<red>{player}: {message}", 0, "exenova.chat.staff", "", false),
                new Channel("local", List.of("l"), "<gray>{message}", 100, "", "", true)
        ), "global");
    }

    @Test
    void resolvesByNameAndAlias() {
        ChannelRegistry r = registry();
        assertEquals("staff", r.resolve("staff").orElseThrow().name());
        assertEquals("staff", r.resolve("sc").orElseThrow().name());
        assertEquals("staff", r.resolve("A").orElseThrow().name(), "alias lookup is case-insensitive");
        assertTrue(r.resolve("nope").isEmpty());
    }

    @Test
    void localChannelsAreFlagged() {
        ChannelRegistry r = registry();
        assertTrue(r.resolve("local").orElseThrow().isLocal());
        assertFalse(r.resolve("global").orElseThrow().isLocal());
    }

    @Test
    void defaultChannelResolves() {
        assertEquals("global", registry().defaultChannel().name());
        // Falls back to the first channel when the configured default is unknown.
        ChannelRegistry odd = ChannelRegistry.fromMap(List.of(new Channel("only", List.of(), null, 0, "", "", true)), "missing");
        assertEquals("only", odd.defaultChannel().name());
    }

    @Test
    void membershipFocusFallsBackToDefault() {
        ChannelRegistry r = registry();
        ChannelMembership m = new ChannelMembership();
        UUID player = UUID.randomUUID();
        assertEquals("global", m.active(player, r).name(), "no focus -> default");
        m.setFocus(player, r.resolve("staff").orElseThrow());
        assertEquals("staff", m.active(player, r).name());
        m.clear(player);
        assertEquals("global", m.active(player, r).name());
    }

    @Test
    void emptyConfigYieldsGlobalChannel() {
        ChannelRegistry r = ChannelRegistry.fromConfig(null);
        assertEquals("global", r.defaultChannel().name());
        assertTrue(r.resolve("g").isPresent());
    }
}
