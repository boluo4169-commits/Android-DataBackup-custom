package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 恢复前冲突预览的统计口径；与 2026-11-10 真机 tar 空跑结果对齐。 */
class RestoreConflictPreviewTest {
    private val archive = listOf("DCIM/", "DCIM/a.jpg", "DCIM/sub/", "DCIM/sub/b.jpg", "DCIM/only-backup.jpg")
    private val existing = setOf("DCIM/a.jpg", "DCIM/sub/b.jpg", "DCIM/only-local.jpg")

    @Test
    fun overwrite_sameNameCovered_newOnesAdded() {
        val p = computeConflictPreview(archive, existing, RestoreConflictStrategy.OVERWRITE, false)
        assertEquals(2, p.overwrite)        // a.jpg + sub/b.jpg
        assertEquals(1, p.added)            // only-backup.jpg
        assertEquals(0, p.removed)
        assertEquals(2, p.conflicts)
    }

    @Test
    fun skip_keepsLocalFiles_butStillAddsNewOnes() {
        val p = computeConflictPreview(archive, existing, RestoreConflictStrategy.SKIP, false)
        assertEquals(2, p.skip)
        assertEquals(1, p.added)
        assertEquals(0, p.overwrite)
    }

    @Test
    fun keepBoth_countsOldFileRenames() {
        val p = computeConflictPreview(archive, existing, RestoreConflictStrategy.KEEP_BOTH, false)
        assertEquals(2, p.keepBoth)
        assertEquals(1, p.added)
    }

    @Test
    fun cleanRestoring_winsAndCountsRemovedLocalOnlyFiles() {
        // 决策 A：干净恢复优先，策略选择器置灰；本机独有文件会被 --recursive-unlink 清掉
        val p = computeConflictPreview(archive, existing, RestoreConflictStrategy.SKIP, true)
        assertEquals(2, p.overwrite)
        assertEquals(0, p.skip)
        assertEquals(1, p.removed)          // only-local.jpg
        assertEquals(1, p.added)
    }

    @Test
    fun trashedAndPendingEntriesAreExcluded() {
        val withTrash = archive + listOf("DCIM/.trashed-1234", "DCIM/.pending-99", "DCIM/.Trashed-1")
        val p = computeConflictPreview(withTrash, existing, RestoreConflictStrategy.OVERWRITE, false)
        assertEquals(3, p.excluded)
        assertEquals(2, p.overwrite)
    }

    @Test
    fun directoryEntriesAreIgnored() {
        val p = computeConflictPreview(listOf("DCIM/", "DCIM/sub/"), emptySet(), RestoreConflictStrategy.OVERWRITE, false)
        assertEquals(0, p.overwrite)
        assertEquals(0, p.added)
        assertTrue(p.isEmpty)
    }

    @Test
    fun dotSlashPrefixIsNormalized() {
        val p = computeConflictPreview(listOf("./DCIM/a.jpg"), setOf("DCIM/a.jpg"), RestoreConflictStrategy.SKIP, false)
        assertEquals(1, p.skip)
        assertEquals(0, p.added)
    }

    @Test
    fun noConflict_doesNotNeedConfirmation() {
        val p = computeConflictPreview(listOf("DCIM/new.jpg"), emptySet(), RestoreConflictStrategy.OVERWRITE, false)
        assertEquals(1, p.added)
        assertFalse(p.needsConfirmation)
    }

    @Test
    fun conflict_needsConfirmation() {
        val p = computeConflictPreview(listOf("DCIM/a.jpg"), setOf("DCIM/a.jpg"), RestoreConflictStrategy.OVERWRITE, false)
        assertTrue(p.needsConfirmation)
    }

    @Test
    fun plus_accumulatesAcrossDirectories() {
        val a = computeConflictPreview(listOf("DCIM/a.jpg"), setOf("DCIM/a.jpg"), RestoreConflictStrategy.SKIP, false)
        val b = computeConflictPreview(listOf("Pictures/b.jpg"), emptySet(), RestoreConflictStrategy.SKIP, false)
        val sum = a + b
        assertEquals(1, sum.skip)
        assertEquals(1, sum.added)
    }

    @Test
    fun emptyInput_isEmpty() {
        assertTrue(computeConflictPreview(emptyList(), emptySet(), RestoreConflictStrategy.OVERWRITE, false).isEmpty)
    }
}
