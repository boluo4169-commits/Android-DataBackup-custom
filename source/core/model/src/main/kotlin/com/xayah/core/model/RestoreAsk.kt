package com.xayah.core.model

/**
 * 「逐个问」（ASK 策略）的一次用户决定。
 *
 * - [strategy] 用户为**当前目录**选定的具体策略，只能是 [ASKABLE_STRATEGIES] 之一；
 * - [applyToAll] 是否把该策略沿用到本次恢复任务剩下的所有目录（「本次全部按这个来」）。
 *
 * 取消用 `null` 表示，语义与现有预览弹窗一致：跳过当前目录。
 */
data class RestoreAskDecision(
    val strategy: RestoreConflictStrategy,
    val applyToAll: Boolean,
)

/** ASK 弹窗里可选的具体策略。刻意不含 ASK 本身，避免「选了询问又继续询问」。 */
val ASKABLE_STRATEGIES: List<RestoreConflictStrategy> = listOf(
    RestoreConflictStrategy.OVERWRITE,
    RestoreConflictStrategy.SKIP,
    RestoreConflictStrategy.KEEP_BOTH,
)

/**
 * 是否要为这个目录弹「逐个问」窗。
 *
 * 只在「策略 = ASK + 干净恢复关 + 确实有同名冲突」时询问：
 * - 干净恢复开启时策略选择器本就置灰，`--recursive-unlink` 优先于一切策略，问了也不生效；
 * - 没有冲突就没有可问的，静默照常恢复，不打扰用户。
 * - 归档条目列不出来（[preview] 为 null）时不询问，走兜底参数，绝不因为预览失败卡住流程。
 */
fun shouldAskForStrategy(
    strategy: RestoreConflictStrategy,
    cleanRestoring: Boolean,
    preview: ConflictPreview?,
): Boolean = strategy == RestoreConflictStrategy.ASK
    && cleanRestoring.not()
    && preview != null
    && preview.conflicts > 0
