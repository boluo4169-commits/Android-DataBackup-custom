package com.xayah.core.model

/**
 * 恢复前冲突预览的统计结果（按「文件」计，目录条目不计）。
 *
 * - [overwrite] 本机已有同名文件、会被备份内容覆盖
 * - [skip] 本机已有同名文件、按策略跳过（本机内容保持不变）
 * - [keepBoth] 本机已有同名文件、旧文件改名留档（`.~1~`）
 * - [added] 归档里有、本机没有，会新增
 * - [removed] 干净恢复时本机独有、会被 `--recursive-unlink` 清掉
 * - [excluded] 归档里被回收站 / 写入中排除规则跳过的条目
 */
data class ConflictPreview(
    val overwrite: Int = 0,
    val skip: Int = 0,
    val keepBoth: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val excluded: Int = 0,
) {
    operator fun plus(other: ConflictPreview) = ConflictPreview(
        overwrite + other.overwrite,
        skip + other.skip,
        keepBoth + other.keepBoth,
        added + other.added,
        removed + other.removed,
        excluded + other.excluded,
    )

    /** 需要用户决策的「同名冲突」总数。 */
    val conflicts: Int get() = overwrite + skip + keepBoth

    /** 是否有值得弹窗告知的内容：同名冲突、干净恢复会删文件、或排除项。 */
    val needsConfirmation: Boolean get() = conflicts > 0 || removed > 0 || excluded > 0

    val isEmpty: Boolean get() = overwrite == 0 && skip == 0 && keepBoth == 0 && added == 0 && removed == 0 && excluded == 0
}

/** 归档条目数上限，防止超大相册目录把预览结果一次性读进内存拖慢 / 撑爆。 */
const val MAX_PREVIEW_ENTRIES = 20_000

/**
 * 计算「这次恢复会动多少东西」——纯函数，不碰文件系统，便于 JVM 单测。
 *
 * [archiveEntries] 是 `tar -tf` 的输出（相对解压 `-C` 目录的路径）；
 * [existingRelativePaths] 是本机已存在文件、同样相对该目录的路径集合。
 * 两者口径一致才能正确判定「同名」。
 *
 * 与真机实测的 tar 行为对齐：
 * - `--skip-old-files` 只跳过同名**文件**，已存在的目录仍会进入，所以目录条目不参与统计；
 * - `--backup=numbered` 把旧文件改名成 `xxx.~1~`，归到 [ConflictPreview.keepBoth]；
 * - `--recursive-unlink`（干净恢复）优先于一切策略，同名一律覆盖，本机独有文件会被清掉。
 */
fun computeConflictPreview(
    archiveEntries: List<String>,
    existingRelativePaths: Set<String>,
    strategy: RestoreConflictStrategy,
    cleanRestoring: Boolean,
    excludePrefixes: List<String> = listOf(".trashed-", ".pending-", ".Trashed-", ".Pending-"),
): ConflictPreview {
    fun normalize(entry: String): String = entry
        .removePrefix("./")
        .trimStart('/')
        .trim()

    fun isExcluded(name: String): Boolean = excludePrefixes.any { name.startsWith(it) }

    var excluded = 0
    val archiveFiles = HashSet<String>(archiveEntries.size.coerceAtMost(MAX_PREVIEW_ENTRIES))
    for (raw in archiveEntries) {
        val entry = normalize(raw)
        if (entry.isEmpty() || entry.endsWith("/")) continue // 目录条目
        val name = entry.substringAfterLast('/')
        if (isExcluded(name)) {
            excluded++
            continue
        }
        archiveFiles.add(entry)
    }

    val existing = HashSet<String>(existingRelativePaths.size)
    for (raw in existingRelativePaths) {
        val entry = normalize(raw)
        if (entry.isEmpty() || entry.endsWith("/")) continue
        existing.add(entry)
    }

    val conflicts = archiveFiles.intersect(existing).size
    val added = archiveFiles.size - conflicts
    val removed = if (cleanRestoring) existing.size - conflicts else 0

    return when {
        cleanRestoring -> ConflictPreview(overwrite = conflicts, added = added, removed = removed, excluded = excluded)
        // ASK 尚未选定策略，预览按「覆盖」口径给出冲突总数供弹窗展示；
        // 用户选定后调用方会用具体策略重算一次，完成页统计不会用这份中性结果。
        strategy == RestoreConflictStrategy.OVERWRITE || strategy == RestoreConflictStrategy.ASK ->
            ConflictPreview(overwrite = conflicts, added = added, excluded = excluded)
        strategy == RestoreConflictStrategy.SKIP ->
            ConflictPreview(skip = conflicts, added = added, excluded = excluded)
        else -> ConflictPreview(keepBoth = conflicts, added = added, excluded = excluded)
    }
}
