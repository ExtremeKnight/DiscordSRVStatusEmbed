package com.exenova.link.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextFilterTest {
    @Test
    void censorsWordsCaseInsensitively() {
        assertEquals("**** off", TextFilter.censorWords("darn off", List.of("darn")));
        assertEquals("****", TextFilter.censorWords("DARN", List.of("darn")));
        assertEquals("clean", TextFilter.censorWords("clean", List.of("darn")));
        assertEquals("x", TextFilter.censorWords("x", List.of()));
    }

    @Test
    void detectsDiscordInvites() {
        assertTrue(TextFilter.containsInvite("join discord.gg/abc123 now"));
        assertTrue(TextFilter.containsInvite("https://discord.com/invite/xyz"));
        assertTrue(TextFilter.containsInvite("DISCORD.GG/AbC"));
        assertFalse(TextFilter.containsInvite("just a normal message"));
    }

    @Test
    void detectsUrls() {
        assertTrue(TextFilter.containsUrl("see https://example.com/page"));
        assertTrue(TextFilter.containsUrl("go to www.example.com"));
        assertTrue(TextFilter.containsUrl("my site is example.org"));
        assertFalse(TextFilter.containsUrl("no links here"));
    }

    @Test
    void stripsInvitesButKeepsOtherText() {
        assertEquals("join [x] please", TextFilter.stripInvites("join discord.gg/abc please", "[x]"));
    }

    @Test
    void stripUrlsRemovesInvitesAndPlainUrls() {
        String out = TextFilter.stripUrls("a discord.gg/abc b https://x.com c", "#");
        assertFalse(TextFilter.containsUrl(out.replace("#", "")));
        assertTrue(out.startsWith("a #"));
    }

    @Test
    void nullsAreSafe() {
        assertNull(TextFilter.censorWords(null, List.of("x")));
        assertFalse(TextFilter.containsInvite(null));
        assertNull(TextFilter.stripUrls(null, "#"));
    }
}
