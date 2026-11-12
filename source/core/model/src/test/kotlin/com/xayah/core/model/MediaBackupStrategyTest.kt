package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBackupStrategyTest {

    @Test
    fun fromIdMapsKnownValues() {
        assertEquals(MediaBackupStrategy.REPLACE, MediaBackupStrategy.fromId(0))
        assertEquals(MediaBackupStrategy.SKIP, MediaBackupStrategy.fromId(1))
        assertEquals(MediaBackupStrategy.COPY, MediaBackupStrategy.fromId(2))
    }

    @Test
    fun fromIdFallsBackToDefaultForUnknownValues() {
        assertEquals(MediaBackupStrategy.DEFAULT, MediaBackupStrategy.fromId(-1))
        assertEquals(MediaBackupStrategy.DEFAULT, MediaBackupStrategy.fromId(99))
        assertEquals(MediaBackupStrategy.REPLACE, MediaBackupStrategy.DEFAULT)
    }

    @Test
    fun archiveOnlyForCopyWithExistingMain() {
        for (strategy in MediaBackupStrategy.entries) {
            assertFalse(shouldArchiveMainBackup(strategy, hasExistingMain = false))
        }
        assertFalse(shouldArchiveMainBackup(MediaBackupStrategy.REPLACE, hasExistingMain = true))
        assertFalse(shouldArchiveMainBackup(MediaBackupStrategy.SKIP, hasExistingMain = true))
        assertTrue(shouldArchiveMainBackup(MediaBackupStrategy.COPY, hasExistingMain = true))
    }

    @Test
    fun skipOnlyForSkipWithExistingMain() {
        for (strategy in MediaBackupStrategy.entries) {
            assertFalse(shouldSkipExistingBackup(strategy, hasExistingMain = false))
        }
        assertFalse(shouldSkipExistingBackup(MediaBackupStrategy.REPLACE, hasExistingMain = true))
        assertFalse(shouldSkipExistingBackup(MediaBackupStrategy.COPY, hasExistingMain = true))
        assertTrue(shouldSkipExistingBackup(MediaBackupStrategy.SKIP, hasExistingMain = true))
    }

    @Test
    fun skipAndArchiveAreMutuallyExclusive() {
        for (strategy in MediaBackupStrategy.entries) {
            for (hasMain in listOf(false, true)) {
                assertFalse(
                    shouldSkipExistingBackup(strategy, hasMain) && shouldArchiveMainBackup(strategy, hasMain)
                )
            }
        }
    }
}
