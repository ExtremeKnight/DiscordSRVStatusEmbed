package com.exenova.link.chat.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChannelMembershipTest {
    private ChannelRegistry registry() {
        return ChannelRegistry.fromMap(List.of(
                new Channel("global", List.of("g"), "<white>{message}", 0, "", "100", true),
                new Channel("staff", List.of("sc"), "<red>{message}", 0, "", "200", false),
                new Channel("events", List.of("e"), "<gold>{message}", 0, "", "", true)
        ), "global");
    }

    @Test
    void subscribeAndUnsubscribe() {
        ChannelMembership m = new ChannelMembership();
        UUID p = UUID.randomUUID();
        Channel staff = registry().resolve("staff").orElseThrow();
        assertFalse(m.isSubscribed(p, staff));
        m.subscribe(p, staff);
        assertTrue(m.isSubscribed(p, staff));
        assertTrue(m.unsubscribe(p, staff));
        assertFalse(m.unsubscribe(p, staff));
    }

    @Test
    void focusAlsoSubscribes() {
        ChannelMembership m = new ChannelMembership();
        UUID p = UUID.randomUUID();
        Channel staff = registry().resolve("staff").orElseThrow();
        m.setFocus(p, staff);
        assertTrue(m.isSubscribed(p, staff));
        assertEquals("staff", m.focus(p));
    }

    @Test
    void joinDefaultsSubscribesAutoJoinChannelsAndFocusesDefault() {
        ChannelRegistry r = registry();
        ChannelMembership m = new ChannelMembership();
        UUID p = UUID.randomUUID();
        m.joinDefaults(p, r, new AlwaysAllowed());
        assertTrue(m.isSubscribed(p, r.resolve("global").orElseThrow()), "global is auto-join");
        assertTrue(m.isSubscribed(p, r.resolve("events").orElseThrow()), "events is auto-join");
        assertFalse(m.isSubscribed(p, r.resolve("staff").orElseThrow()), "staff is not auto-join");
        assertEquals("global", m.focus(p));
    }

    @Test
    void byDiscordChannelResolves() {
        ChannelRegistry r = registry();
        assertEquals("global", r.byDiscordChannel("100").orElseThrow().name());
        assertEquals("staff", r.byDiscordChannel("200").orElseThrow().name());
        assertTrue(r.byDiscordChannel("999").isEmpty());
        assertTrue(r.byDiscordChannel("").isEmpty());
    }

    @Test
    void clearRemovesEverything() {
        ChannelMembership m = new ChannelMembership();
        UUID p = UUID.randomUUID();
        Channel staff = registry().resolve("staff").orElseThrow();
        m.setFocus(p, staff);
        m.clear(p);
        assertFalse(m.isSubscribed(p, staff));
        assertNull(m.focus(p));
    }

    /** Minimal Permissible that grants everything, for joinDefaults. */
    private static final class AlwaysAllowed implements org.bukkit.permissions.Permissible {
        public boolean isPermissionSet(String name) { return true; }
        public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return true; }
        public boolean hasPermission(String name) { return true; }
        public boolean hasPermission(org.bukkit.permissions.Permission perm) { return true; }
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin p, String n, boolean v) { return null; }
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin p) { return null; }
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin p, String n, boolean v, int t) { return null; }
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin p, int t) { return null; }
        public void removeAttachment(org.bukkit.permissions.PermissionAttachment a) { }
        public void recalculatePermissions() { }
        public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Set.of(); }
        public boolean isOp() { return true; }
        public void setOp(boolean value) { }
    }
}
