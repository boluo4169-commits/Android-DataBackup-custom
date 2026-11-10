package com.xayah.core.model.util

/**
 * 参与历史版本序号计算的条目。
 *
 * [groupKey] 用「同一份备份的多个版本」共有的字段组成（名称 / 路径 / 云端账号 / 远端目录）。
 * 用 List 而不是拼接字符串，避免路径里出现分隔符时把两组错误合并。
 */
data class PreserveItem(
    val id: Long,
    val groupKey: List<String>,
    val preserveId: Long,
)

/**
 * 给历史版本编号：同一组内按 [PreserveItem.preserveId] 降序，最新的 = 护盾 1。
 *
 * - 主版本（preserveId == 0）不参与编号，调用方应显示为 0 / 不显示护盾序号。
 * - 序号由 **preserveId** 而不是 lastBackupTime 决定：文件侧「保护」只改写 indexInfo，
 *   lastBackupTime 会整组保持原值，用它排序会退化成不稳定顺序。
 * - 兼容历史数据：旧版本用 13 位毫秒戳，新版本用 14 位 yyyyMMddHHmmss；数值上
 *   14 位（约 2e13）恒大于 13 位（约 1.7e12），因此混合排序时新版本自然排在前面。
 */
fun buildPreserveIndexMap(items: List<PreserveItem>): Map<Long, Int> =
    items.groupBy { it.groupKey }
        .values
        .flatMap { group ->
            group.filter { it.preserveId != 0L }
                .sortedByDescending { it.preserveId }
                .mapIndexed { index, item -> item.id to (index + 1) }
        }
        .toMap()
