package com.xayah.core.model

import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.database.preserveArchiveRelativeDir
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统一解析实体「实际使用的」归档相对目录：
 * - `pkgOnly = true`  → [PackageEntity.legacyArchivesRelativeDir]（纯包名）
 * - `pkgOnly = false` → [PackageEntity.archivesRelativeDir]（带应用名_包名）
 *
 * 各消费点（备份/恢复服务、详情页「文件路径」、云端删除）都应通过本函数取，
 * 避免各写一套导致「显示的路径」与「服务器上的路径」对不上，以及跨父目录 rename
 * 在 FTP 等不支持自动建父目录的服务上 IOException 崩溃。
 */
class ArchivesDirResolutionTest {
    private fun makeEntity(label: String, pkg: String, preserveId: Long) = PackageEntity(
        id = 0L,
        indexInfo = PackageIndexInfo(
            opType = OpType.BACKUP,
            packageName = pkg,
            userId = 0,
            compressionType = CompressionType.ZSTD,
            preserveId = preserveId,
            cloud = "",
            backupDir = "pad",
        ),
        packageInfo = PackageInfo(
            label = label,
            versionName = "1.0",
            versionCode = 1L,
            flags = 0,
            firstInstallTime = 0L,
            lastUpdateTime = 0L,
        ),
        extraInfo = PackageExtraInfo(
            uid = 0,
            hasKeystore = false,
            permissions = listOf(),
            ssaid = "",
            lastBackupTime = 0L,
            blocked = false,
            activated = false,
            firstUpdated = true,
            enabled = true,
        ),
        dataStates = PackageDataStates(),
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(),
    )

    @Test
    fun `pkgOnly true returns legacy package-only dir`() {
        val e = makeEntity("计算器", "com.coloros.calculator", preserveId = 0L)
        assertEquals("com.coloros.calculator/user_0", e.resolveArchivesRelativeDir(pkgOnly = true))
    }

    @Test
    fun `pkgOnly false returns labeled dir`() {
        val e = makeEntity("计算器", "com.coloros.calculator", preserveId = 0L)
        assertEquals("计算器_com.coloros.calculator/user_0", e.resolveArchivesRelativeDir(pkgOnly = false))
    }

    @Test
    fun `preserveId non-zero is appended to both forms`() {
        val e = makeEntity("计算器", "com.coloros.calculator", preserveId = 20260911232050L)
        assertEquals(
            "com.coloros.calculator/user_0@20260911232050",
            e.resolveArchivesRelativeDir(pkgOnly = true),
        )
        assertEquals(
            "计算器_com.coloros.calculator/user_0@20260911232050",
            e.resolveArchivesRelativeDir(pkgOnly = false),
        )
    }

    @Test
    fun `empty label falls back to package-only even when pkgOnly is false`() {
        val e = makeEntity("", "com.example", preserveId = 0L)
        assertEquals("com.example/user_0", e.resolveArchivesRelativeDir(pkgOnly = false))
    }

    @Test
    fun `preserve appends the timestamp to the source dir`() {
        assertEquals("com.pkg/user_0@20260101", preserveArchiveRelativeDir("com.pkg/user_0", 20260101L))
        assertEquals("应用名_com.pkg/user_0@20260101", preserveArchiveRelativeDir("应用名_com.pkg/user_0", 20260101L))
    }

    @Test
    fun `preserve replaces an existing timestamp instead of stacking`() {
        // 连续保护两次不能得到 @旧@新
        assertEquals("com.pkg/user_0@20260202", preserveArchiveRelativeDir("com.pkg/user_0@20260101", 20260202L))
    }

    @Test
    fun `preserve keeps the source parent dir`() {
        // 目标必须与源同父目录，不能跳到另一个父目录（跨父目录 rename 在 FTP 上会崩）
        val src = "com.pkg/user_0"
        val dst = preserveArchiveRelativeDir(src, 20260101L)
        assertEquals(src.substringBeforeLast('/'), dst.substringBeforeLast('/'))
    }
}
