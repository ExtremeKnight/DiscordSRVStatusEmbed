package com.exenova.link.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MentionsTest {
    private static final List<String> ONLINE = List.of("Steve", "Alex", "Notch_01");

    @Test
    void findsOnlineMentions() {
        assertEquals(List.of("Steve"), Mentions.find("hey @steve how are you", ONLINE));
        assertEquals(List.of("Steve", "Alex"), Mentions.find("@Steve and @alex", ONLINE));
    }

    @Test
    void requiresTheAtPrefix() {
        assertTrue(Mentions.find("steve is here", ONLINE).isEmpty());
    }

    @Test
    void ignoresUnknownAndDeduplicates() {
        assertTrue(Mentions.find("@ghost", ONLINE).isEmpty());
        assertEquals(List.of("Steve"), Mentions.find("@steve @STEVE @Steve", ONLINE), "deduped, canonical case");
    }

    @Test
    void handlesUnderscoresAndDigits() {
        assertEquals(List.of("Notch_01"), Mentions.find("ping @Notch_01 pls", ONLINE));
    }

    @Test
    void mentionsHelper() {
        assertTrue(Mentions.mentions("yo @alex", "Alex"));
        assertFalse(Mentions.mentions("yo alex", "Alex"));
    }

    @Test
    void emptyInputsAreSafe() {
        assertTrue(Mentions.find(null, ONLINE).isEmpty());
        assertTrue(Mentions.find("@steve", List.of()).isEmpty());
    }
}
