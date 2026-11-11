package com.xayah.core.model

/**
 * 文件备份「自动留历史版本」的版本裁剪纯函数，不碰文件系统，便于 JVM 单测。
 *
 * 输入是同一目录（name + cloud + backupDir）下所有版本的 [preserveIds]，
 * 返回**超出上限、应被删除**的那些历史版本 id：
 * - 主版本 `preserveId = 0` 永不参与裁剪；
 * - 14 位 `yyyyMMddHHmmss` 按数值降序即时间降序；历史 13 位毫秒戳数值小一个数量级、
 *   会排在后面（即 Plan 第 4 条的「格式串序」，展示层由 Phase 0 的 `buildPreserveIndexMap` 兼容）；
 * - [maxCount] 非法值（0 / 负数）一律按 1 处理，**绝不把所有历史版本一次删光**。
 */
fun pickExpiredPreserves(preserveIds: List<Long>, maxCount: Int): List<Long> {
    val preserved = preserveIds.filter { it != 0L }.distinct().sortedDescending()
    return preserved.drop(maxCount.coerceAtLeast(1))
}

/**
 * 历史版本上限的有效区间。设置页滑块是 1..5，但 DataStore 可能被旧值或异常写入污染，
 * 服务层统一过一遍，避免 0 把版本全删、或误设成 100 撑爆存储。
 */
fun sanitizeMaxPreserveMediaCount(value: Int): Int = value.coerceIn(1, 5)
