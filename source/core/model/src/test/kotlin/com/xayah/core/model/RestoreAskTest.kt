package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「逐个问」的触发条件、可选策略与预览口径。 */
class RestoreAskTest {

    @Test
    fun askableStrategies_excludeAskItself() {
        assertEquals(
            listOf(
                RestoreConflictStrategy.OVERWRITE,
                RestoreConflictStrategy.SKIP,
                RestoreConflictStrategy.KEEP_BOTH,
            ),
            ASKABLE_STRATEGIES,
        )
        assertFalse(ASKABLE_STRATEGIES.contains(RestoreConflictStrategy.ASK))
        // 每个可选项都必须能生成固定 tar 参数，不能混进自由文本
        ASKABLE_STRATEGIES.forEach { strategy ->
            assertTrue(resolveRestoreConflictArgs(false, strategy).startsWith("--"))
        }
    }

    @Test
    fun shouldAsk_onlyWhenAskAndCleanOffAndHasConflicts() {
        val conflicts = ConflictPreview(overwrite = 2, added = 1)
        assertTrue(shouldAskForStrategy(RestoreConflictStrategy.ASK, false, conflicts))

        // 固定策略不询问
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.OVERWRITE, false, conflicts))
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.SKIP, false, conflicts))
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.KEEP_BOTH, false, conflicts))
        // 干净恢复优先，问了也不生效
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.ASK, true, conflicts))
        // 没有冲突不打扰用户
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.ASK, false, ConflictPreview(added = 3)))
        // 归档列不出来时不卡流程
        assertFalse(shouldAskForStrategy(RestoreConflictStrategy.ASK, false, null))
    }

    @Test
    fun askPreview_usesNeutralOverwriteCountThenRecomputedByChosenStrategy() {
        val archive = listOf("P5TEST/a.jpg", "P5TEST/only-backup.jpg")
        val existing = setOf("P5TEST/a.jpg")

        // 询问前：中性口径给出冲突总数，不预设跳过/留档
        val neutral = computeConflictPreview(archive, existing, RestoreConflictStrategy.ASK, cleanRestoring = false)
        assertEquals(1, neutral.conflicts)
        assertEquals(1, neutral.overwrite)
        assertEquals(0, neutral.skip)
        assertEquals(0, neutral.keepBoth)
        assertEquals(1, neutral.added)

        // 用户选定后：按具体策略重算，完成页统计才准确
        val skipped = computeConflictPreview(archive, existing, RestoreConflictStrategy.SKIP, cleanRestoring = false)
        assertEquals(1, skipped.conflicts)
        assertEquals(0, skipped.overwrite)
        assertEquals(1, skipped.skip)
        assertEquals(0, skipped.keepBoth)

        val kept = computeConflictPreview(archive, existing, RestoreConflictStrategy.KEEP_BOTH, cleanRestoring = false)
        assertEquals(0, kept.overwrite)
        assertEquals(0, kept.skip)
        assertEquals(1, kept.keepBoth)
    }

    @Test
    fun decision_carriesStrategyAndApplyToAll() {
        val one = RestoreAskDecision(RestoreConflictStrategy.SKIP, applyToAll = false)
        val all = RestoreAskDecision(RestoreConflictStrategy.KEEP_BOTH, applyToAll = true)
        assertEquals(RestoreConflictStrategy.SKIP, one.strategy)
        assertFalse(one.applyToAll)
        assertTrue(all.applyToAll)
    }
}
