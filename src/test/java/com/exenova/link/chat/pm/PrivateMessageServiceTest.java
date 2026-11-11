package com.exenova.link.chat.pm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PrivateMessageServiceTest {
    private PrivateMessageService service;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @BeforeEach
    void setUp() { service = new PrivateMessageService(); }

    @Test
    void replyTargetIsSetBothWays() {
        service.recordConversation(a, b);
        assertEquals(b, service.replyTarget(a));
        assertEquals(a, service.replyTarget(b));
    }

    @Test
    void replyTargetUpdatesToMostRecent() {
        UUID c = UUID.randomUUID();
        service.recordConversation(a, b);
        service.recordConversation(a, c);
        assertEquals(c, service.replyTarget(a));
    }

    @Test
    void ignoreIsIdempotentAndReversible() {
        assertTrue(service.ignore(a, b));
        assertFalse(service.ignore(a, b), "already ignored");
        assertTrue(service.isIgnoring(a, b));
        assertFalse(service.isIgnoring(b, a), "ignore is one-directional");
        assertTrue(service.unignore(a, b));
        assertFalse(service.unignore(a, b));
        assertFalse(service.isIgnoring(a, b));
    }

    @Test
    void cannotIgnoreSelf() {
        assertFalse(service.ignore(a, a));
        assertFalse(service.isIgnoring(a, a));
    }

    @Test
    void socialSpyToggles() {
        assertTrue(service.toggleSocialSpy(a));
        assertTrue(service.isSpying(a));
        assertFalse(service.toggleSocialSpy(a));
        assertFalse(service.isSpying(a));
    }

    @Test
    void clearRemovesPlayerFromAllState() {
        service.recordConversation(a, b);
        service.toggleSocialSpy(a);
        service.ignore(a, b);
        service.clear(a);
        assertNull(service.replyTarget(a));
        assertNull(service.replyTarget(b), "a should no longer be b's reply target");
        assertFalse(service.isSpying(a));
        assertFalse(service.isIgnoring(a, b));
    }
}
