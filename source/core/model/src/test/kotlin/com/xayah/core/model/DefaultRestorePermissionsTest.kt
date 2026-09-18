package com.xayah.core.model

import com.xayah.core.model.util.defaultRestorePermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「恢复权限」默认值随 ROM 变化。
 *
 * 背景：澎湃/MIUI 上这个开关不是"还原备份时的授权状态"，而是恢复时**强制授予全部权限**
 * （见 `restore_permissions_help`），默认打开会让用户恢复后拿到一堆本不该有的授权。
 * 因此澎湃上默认关闭、交给系统自己恢复；其它 ROM 保持原来的默认（开）。
 *
 * 这里锁住映射本身；ROM 判定（读 `ro.mi.os.version.name`）在 `RomUtil`，
 * 需要真机才能验，纯 JVM 跑不了。
 */
class DefaultRestorePermissionsTest {
    @Test
    fun `hyperos defaults to off`() {
        assertFalse(defaultRestorePermissions(isHyperOs = true))
    }

    @Test
    fun `other roms keep the previous default`() {
        assertTrue(defaultRestorePermissions(isHyperOs = false))
    }
}
