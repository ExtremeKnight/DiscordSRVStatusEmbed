package com.exenova.link.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {
    @Test
    void acceptsOnlyPlainBackupNames() {
        assertTrue(BackupService.isValidBackupName("config-1700000000000.yml"));
        assertFalse(BackupService.isValidBackupName("../config-1.yml"));
        assertFalse(BackupService.isValidBackupName("config-1.yml/../../x"));
        assertFalse(BackupService.isValidBackupName("config-abc.yml"));
        assertFalse(BackupService.isValidBackupName("other.yml"));
        assertFalse(BackupService.isValidBackupName(null));
    }
}
