package com.xayah.core.model.util

import android.os.SystemProperties

/**
 * ROM 判定（读系统属性，走 :core:hiddenapi 的隐藏 API 桩）。
 *
 * 放在本模块的原因：`core:hiddenapi` 是以 **compileOnly** 被引用的（编译期签名、运行时用系统实现），
 * 它的自定义类不会打进 APK；而本模块被 `core:datastore` 以 implementation 依赖，运行时可正常加载。
 *
 * 用途：某些恢复行为在不同 ROM 上语义不同，需要按 ROM 改默认值
 * （例：澎湃/MIUI 的「恢复权限」是**强制授予全部权限**，与"还原备份时的授权状态"的预期不符）。
 */
object RomUtil {
    /**
     * 是否是澎湃系统（HyperOS）。
     *
     * 判据：澎湃上 `ro.mi.os.version.name` 非空（如 "2.0"）；MIUI 只有 `ro.miui.ui.version.name`，
     * 该属性为空；一加/OPPO 等 ColorOS 机型两个都为空（2026-09-18 在 OPD2413 上实测为空）。
     * 读属性失败时一律按"不是澎湃"处理，避免影响其它机型。
     */
    val isHyperOs: Boolean by lazy {
        runCatching {
            SystemProperties.get("ro.mi.os.version.name").orEmpty().isNotEmpty()
        }.getOrDefault(false)
    }

    /** 是否是 MIUI（不含澎湃；澎湃上该属性可能仍存在，故先排除澎湃） */
    val isMiui: Boolean by lazy {
        if (isHyperOs) false
        else runCatching {
            SystemProperties.get("ro.miui.ui.version.name").orEmpty().isNotEmpty()
        }.getOrDefault(false)
    }
}
