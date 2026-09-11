package com.xayah.core.model

import com.xayah.core.model.database.PackageDataStats
import com.xayah.core.model.database.PackageDataStates
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.PackageExtraInfo
import com.xayah.core.model.database.PackageIndexInfo
import com.xayah.core.model.database.PackageInfo
import com.xayah.core.model.database.PackageStorageStats
import com.xayah.core.model.util.formatSize
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 引导页的「应用」总大小只应统计**已勾选**的数据类型。
 *
 * 复现用户反馈：只勾选 APK 时引导页显示 552.88 MB，而实际只备份了 APK（207.57 MB）。
 * 数值取自 2026-09-11 的云端备份产物
 * `pad/apps/com.coolapk.market/user_0/package_restore_config.json`（com.coolapk.market / user 0）。
 */
class SelectedDisplayStatsTest {
    private val apkBytes = 217_650_646L      // 207.57 MB
    private val userBytes = 359_501_650L     // 342.85 MB
    private val userDeBytes = 1_457_915L     // 1.39 MB
    private val dataBytes = 1_131_531L       // 1.08 MB

    private fun states(apk: Boolean, user: Boolean, userDe: Boolean, data: Boolean) = PackageDataStates(
        apkState = if (apk) DataState.Selected else DataState.NotSelected,
        userState = if (user) DataState.Selected else DataState.NotSelected,
        userDeState = if (userDe) DataState.Selected else DataState.NotSelected,
        dataState = if (data) DataState.Selected else DataState.NotSelected,
        obbState = DataState.NotSelected,
        mediaState = DataState.NotSelected,
    )

    private fun entity(states: PackageDataStates) = PackageEntity(
        id = 0L,
        indexInfo = PackageIndexInfo(
            opType = OpType.BACKUP,
            packageName = "com.coolapk.market",
            userId = 0,
            compressionType = CompressionType.ZSTD,
            preserveId = 0L,
            cloud = "",
            backupDir = "pad",
        ),
        packageInfo = PackageInfo(
            label = "酷安",
            versionName = "16.5.1",
            versionCode = 2_607_271L,
            flags = 0,
            firstInstallTime = 0L,
            lastUpdateTime = 0L,
        ),
        extraInfo = PackageExtraInfo(
            uid = 10_328,
            hasKeystore = false,
            permissions = listOf(),
            ssaid = "",
            lastBackupTime = 0L,
            blocked = false,
            activated = true,
            firstUpdated = true,
            enabled = true,
        ),
        dataStates = states,
        storageStats = PackageStorageStats(),
        dataStats = PackageDataStats(),
        displayStats = PackageDataStats(
            apkBytes = apkBytes,
            userBytes = userBytes,
            userDeBytes = userDeBytes,
            dataBytes = dataBytes,
        ),
    )

    @Test
    fun `only apk selected counts apk bytes only`() {
        val e = entity(states(apk = true, user = false, userDe = false, data = false))
        assertEquals(apkBytes.toDouble(), e.selectedDisplayStatsBytes, 0.0)
        // 旧口径（六类全加）正是用户看到的虚高值，留作回归对照
        assertEquals((apkBytes + userBytes + userDeBytes + dataBytes).toDouble(), e.displayStatsBytes, 0.0)
    }

    @Test
    fun `selecting more types adds their bytes`() {
        val e = entity(states(apk = true, user = true, userDe = false, data = true))
        assertEquals((apkBytes + userBytes + dataBytes).toDouble(), e.selectedDisplayStatsBytes, 0.0)
    }

    @Test
    fun `all selected equals the legacy total`() {
        val e = entity(states(apk = true, user = true, userDe = true, data = true))
        assertEquals(e.displayStatsBytes, e.selectedDisplayStatsBytes, 0.0)
    }

    @Test
    fun `nothing selected is zero`() {
        val e = entity(states(apk = false, user = false, userDe = false, data = false))
        assertEquals(0.0, e.selectedDisplayStatsBytes, 0.0)
    }

    @Test
    fun `formatted sizes match the reported numbers`() {
        val previous = Locale.getDefault()
        try {
            // formatSize 用 DecimalFormat("#.00")，受默认 Locale 影响，这里固定以便断言
            Locale.setDefault(Locale.US)
            val e = entity(states(apk = true, user = false, userDe = false, data = false))
            assertEquals("207.57 MB", e.selectedDisplayStatsBytes.formatSize())
            assertEquals("552.88 MB", e.displayStatsBytes.formatSize())
        } finally {
            Locale.setDefault(previous)
        }
    }
}
