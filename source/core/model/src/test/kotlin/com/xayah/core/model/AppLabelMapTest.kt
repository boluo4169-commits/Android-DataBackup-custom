package com.xayah.core.model

import com.xayah.core.model.database.LabelAppCrossRefEntity
import com.xayah.core.model.database.LabelEntity
import com.xayah.core.model.util.AppLabelKey
import com.xayah.core.model.util.buildAppLabelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLabelMapTest {
    private fun ref(label: String, pkg: String = "com.example.app", userId: Int = 0, preserveId: Long = 0L) =
        LabelAppCrossRefEntity(label = label, packageName = pkg, userId = userId, preserveId = preserveId)

    private fun labels(vararg names: String) = names.map { LabelEntity(label = it) }

    @Test
    fun emptyRefs_returnsEmptyMap() {
        assertTrue(buildAppLabelMap(emptyList(), labels("游戏")).isEmpty())
    }

    @Test
    fun orphanRef_isFilteredOut() {
        // 标签已删但关联残留（v3.15.0 修过），列表不能把它显示出来
        val map = buildAppLabelMap(
            listOf(ref("已删标签"), ref("游戏")),
            labels("游戏"),
        )
        assertEquals(listOf("游戏"), map[AppLabelKey("com.example.app", 0, 0L)])
    }

    @Test
    fun multipleLabels_areDistinctAndSorted() {
        val map = buildAppLabelMap(
            listOf(ref("常备份"), ref("游戏"), ref("游戏"), ref(" 大体积 ")),
            labels("游戏", "常备份", "大体积"),
        )
        // sorted() 按 Unicode 码点，断言顺序稳定且去重即可
        val result = map.getValue(AppLabelKey("com.example.app", 0, 0L))
        assertEquals(3, result.size)
        assertEquals(result.distinct().sorted(), result)
        assertTrue("大体积" in result)
    }

    @Test
    fun samePackageDifferentUser_isIsolated() {
        val map = buildAppLabelMap(
            listOf(ref("主空间", userId = 0), ref("双开", userId = 999)),
            labels("主空间", "双开"),
        )
        assertEquals(listOf("主空间"), map[AppLabelKey("com.example.app", 0, 0L)])
        assertEquals(listOf("双开"), map[AppLabelKey("com.example.app", 999, 0L)])
    }

    @Test
    fun sameAppDifferentPreserveId_isIsolated() {
        val map = buildAppLabelMap(
            listOf(ref("主版本", preserveId = 0L), ref("保护版本", preserveId = 1700000000L)),
            labels("主版本", "保护版本"),
        )
        assertEquals(listOf("主版本"), map[AppLabelKey("com.example.app", 0, 0L)])
        assertEquals(listOf("保护版本"), map[AppLabelKey("com.example.app", 0, 1700000000L)])
    }

    @Test
    fun blankAndUnknownLabels_areSkipped() {
        val map = buildAppLabelMap(
            listOf(ref(""), ref("   "), ref("幽灵"), ref("有效")),
            labels("有效"),
        )
        assertEquals(1, map.size)
        assertEquals(listOf("有效"), map[AppLabelKey("com.example.app", 0, 0L)])
    }

    @Test
    fun multipleApps_areGroupedByKey() {
        val map = buildAppLabelMap(
            listOf(ref("游戏", "com.a"), ref("工具", "com.b"), ref("常备份", "com.a")),
            labels("游戏", "工具", "常备份"),
        )
        assertEquals(2, map.size)
        assertEquals(2, map.getValue(AppLabelKey("com.a", 0, 0L)).size)
        assertEquals(listOf("工具"), map[AppLabelKey("com.b", 0, 0L)])
    }
}
