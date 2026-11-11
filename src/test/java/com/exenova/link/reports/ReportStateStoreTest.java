package com.exenova.link.reports;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReportStateStoreTest {
    @Test
    void beginResetsPreviousState() {
        ReportStateStore store = new ReportStateStore();
        UUID reporter = UUID.randomUUID();
        store.begin(reporter, UUID.randomUUID(), 0);
        store.reasons().put(reporter, "Griefing");
        store.awaitingDetails().add(reporter);

        UUID target = UUID.randomUUID();
        store.begin(reporter, target, 10);
        assertEquals(target, store.targets().get(reporter));
        assertNull(store.reasons().get(reporter));
        assertFalse(store.awaitingDetails().contains(reporter));
    }

    @Test
    void expireClearsOnlyOldReports() {
        ReportStateStore store = new ReportStateStore();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        store.begin(old, UUID.randomUUID(), 100);
        store.begin(fresh, UUID.randomUUID(), 500);
        store.awaitingDetails().add(old);

        assertEquals(1, store.expireBefore(200));
        assertFalse(store.targets().containsKey(old));
        assertFalse(store.awaitingDetails().contains(old));
        assertTrue(store.targets().containsKey(fresh));
    }
}
