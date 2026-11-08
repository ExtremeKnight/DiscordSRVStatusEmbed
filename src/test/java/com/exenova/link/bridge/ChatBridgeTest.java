package com.exenova.link.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatBridgeTest {
    @Test
    void stripsMinecraftColourCodes() {
        assertEquals("hello world", ChatBridge.stripColors("§ahello &lworld"));
        assertEquals("", ChatBridge.stripColors(null));
    }

    @Test
    void webhookNamesAvoidReservedWords() {
        assertFalse(ChatBridge.webhookName("DiscordFan").toLowerCase().contains("discord"));
        assertFalse(ChatBridge.webhookName("clyde").toLowerCase().contains("clyde"));
        assertEquals("Player", ChatBridge.webhookName("   "));
        assertEquals(80, ChatBridge.webhookName("x".repeat(200)).length());
    }

    @Test
    void truncateAddsEllipsis() {
        assertEquals("abc", ChatBridge.truncate("abc", 5));
        assertEquals("abcd…", ChatBridge.truncate("abcdefgh", 5));
    }
}
