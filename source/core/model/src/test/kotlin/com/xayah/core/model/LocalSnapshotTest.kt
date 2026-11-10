package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSnapshotTest {
    private val archive = listOf(
        "DCIM/",
        "DCIM/a.jpg",
        "DCIM/sub/b.jpg",
        "DCIM/new.jpg",
        "DCIM/.trashed-123-a.jpg",
        "./DCIM/c.jpg",
    )
    private val existing = setOf(
        "DCIM/a.jpg",
        "DCIM/sub/b.jpg",
        "DCIM/c.jpg",
        "DCIM/only-local.jpg",
        "DCIM/sub/",
    )

    @Test
    fun `snapshot keeps only conflicting files and normalizes dot prefix`() {
        assertEquals(
            listOf("DCIM/a.jpg", "DCIM/c.jpg", "DCIM/sub/b.jpg"),
            computeSnapshotPaths(archive, existing),
        )
    }

    @Test
    fun `snapshot excludes trashed pending files and directory entries`() {
        val paths = computeSnapshotPaths(
            archiveEntries = listOf("DCIM/.pending-9-x.jpg", "DCIM/.Trashed-1-y.jpg", "DCIM/"),
            existingRelativePaths = setOf("DCIM/.pending-9-x.jpg", "DCIM/.Trashed-1-y.jpg", "DCIM/"),
        )
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `snapshot ignores entries containing newline because tar list is line based`() {
        val paths = computeSnapshotPaths(
            archiveEntries = listOf("DCIM/bad\nname.jpg"),
            existingRelativePaths = setOf("DCIM/bad\nname.jpg"),
        )
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `snapshot is stable regardless of input order`() {
        assertEquals(
            computeSnapshotPaths(archive, existing),
            computeSnapshotPaths(archive.reversed(), existing.toList().reversed().toSet()),
        )
    }

    @Test
    fun `snapshot is created only for keep both without clean restoring`() {
        val paths = listOf("DCIM/a.jpg")
        assertTrue(shouldCreateLocalSnapshot(RestoreConflictStrategy.KEEP_BOTH, false, paths))
        assertFalse(shouldCreateLocalSnapshot(RestoreConflictStrategy.KEEP_BOTH, true, paths))
        assertFalse(shouldCreateLocalSnapshot(RestoreConflictStrategy.OVERWRITE, false, paths))
        assertFalse(shouldCreateLocalSnapshot(RestoreConflictStrategy.SKIP, false, paths))
        assertFalse(shouldCreateLocalSnapshot(RestoreConflictStrategy.KEEP_BOTH, false, emptyList()))
    }
}
