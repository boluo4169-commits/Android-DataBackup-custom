package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 恢复冲突策略 → tar 参数的纯映射。参数一旦写错，root shell 里是整个解压失败
 * （实测 `--overwrite --overwrite-dir` 互斥，exit 2 且文件一个都不落地），所以锁住。
 */
class RestoreConflictStrategyTest {

    @Test
    fun overwrite_usesSingleOverwriteFlag() {
        val args = resolveRestoreConflictArgs(cleanRestoring = false, RestoreConflictStrategy.OVERWRITE)
        assertEquals("--overwrite", args)
        assertEquals(false, args.contains("--overwrite-dir"))
    }

    @Test
    fun skip_and_keepBoth_mapToVerifiedFlags() {
        assertEquals(
            "--skip-old-files",
            resolveRestoreConflictArgs(false, RestoreConflictStrategy.SKIP),
        )
        assertEquals(
            "--backup=numbered",
            resolveRestoreConflictArgs(false, RestoreConflictStrategy.KEEP_BOTH),
        )
    }

    @Test
    fun cleanRestoring_alwaysWins() {
        // 决策 A：干净恢复优先，策略选择器置灰，避免叠加后策略静默失效
        RestoreConflictStrategy.entries.forEach { strategy ->
            assertEquals(
                "--recursive-unlink",
                resolveRestoreConflictArgs(true, strategy),
            )
        }
    }

    @Test
    fun fromId_defaultsToOverwrite_onUnknownValue() {
        assertEquals(RestoreConflictStrategy.OVERWRITE, RestoreConflictStrategy.DEFAULT)
        assertEquals(RestoreConflictStrategy.OVERWRITE, RestoreConflictStrategy.fromId(-1))
        assertEquals(RestoreConflictStrategy.OVERWRITE, RestoreConflictStrategy.fromId(99))
        assertEquals(RestoreConflictStrategy.SKIP, RestoreConflictStrategy.fromId(1))
        assertEquals(RestoreConflictStrategy.KEEP_BOTH, RestoreConflictStrategy.fromId(2))
        assertEquals(RestoreConflictStrategy.ASK, RestoreConflictStrategy.fromId(3))
    }

    @Test
    fun ask_usesNonDestructiveFallback() {
        // ASK 正常路径会被弹窗选出的策略覆盖；兜底必须是「不覆盖本机旧文件」的最保守参数
        assertEquals(
            "--skip-old-files",
            resolveRestoreConflictArgs(false, RestoreConflictStrategy.ASK),
        )
    }

    @Test
    fun noStrategyContainsShellMetacharacters() {
        // 参数会拼进 root shell 命令，必须是纯固定字符串
        RestoreConflictStrategy.entries.forEach { strategy ->
            val args = resolveRestoreConflictArgs(false, strategy)
            assertEquals(false, args.any { it in "|;&$`'\"\n" })
        }
    }
}
