package com.exenova.link.chat;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmojiTest {
    private static final Map<String, String> MAP = Map.of("smile", "☺", "heart", "❤", "shrug", "¯\\_(ツ)_/¯");

    @Test
    void replacesKnownShortcodes() {
        assertEquals("hi ☺", Emoji.apply("hi :smile:", MAP));
        assertEquals("☺ and ❤", Emoji.apply(":smile: and :heart:", MAP));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals("☺", Emoji.apply(":SMILE:", MAP));
    }

    @Test
    void leavesUnknownTokensUntouched() {
        assertEquals("a :unknown: b", Emoji.apply("a :unknown: b", MAP));
        assertEquals("ratio 10:30 ok", Emoji.apply("ratio 10:30 ok", MAP));
    }

    @Test
    void handlesReplacementWithRegexSpecialChars() {
        assertEquals("¯\\_(ツ)_/¯", Emoji.apply(":shrug:", MAP));
    }

    @Test
    void noMapOrNoColonsIsANoop() {
        assertEquals("plain text", Emoji.apply("plain text", MAP));
        assertEquals(":smile:", Emoji.apply(":smile:", Map.of()));
        assertNull(Emoji.apply(null, MAP));
    }
}
