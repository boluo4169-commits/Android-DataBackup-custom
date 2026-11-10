package com.xayah.core.model

/**
 * 方案②「本机快照版本」：恢复前把会被覆盖的本机旧文件打包成一份归档，
 * 再登记成 `MediaEntity(preserveId != 0)`，App 里就多一个可勾选恢复 / 删除的护盾版本。
 *
 * 本文件只放纯函数，文件系统与数据库操作在 `MediumRestoreUtil.createLocalSnapshot()`。
 */
const val LOCAL_SNAPSHOT_MAX_FILES = MAX_PREVIEW_ENTRIES

private val DEFAULT_TRASHED_PREFIXES = listOf(".trashed-", ".pending-", ".Trashed-", ".Pending-")

/**
 * 算出「需要打进本机快照」的旧文件相对路径。
 *
 * 口径必须与 [computeConflictPreview] 完全一致：归档条目与本机路径都相对解压 `-C` 目录，
 * 目录条目不计，媒体库回收站 / 写入中的文件排除（它们本来就不会被恢复，快照也不该收）。
 * 返回排序后的列表，保证写入 tar `-T` 清单的顺序稳定。
 */
fun computeSnapshotPaths(
    archiveEntries: List<String>,
    existingRelativePaths: Set<String>,
    excludePrefixes: List<String> = DEFAULT_TRASHED_PREFIXES,
): List<String> {
    fun normalize(entry: String): String = entry
        .removePrefix("./")
        .trimStart('/')
        .trim()

    fun isExcluded(name: String): Boolean = excludePrefixes.any { name.startsWith(it) }

    val archiveFiles = HashSet<String>(archiveEntries.size.coerceAtMost(LOCAL_SNAPSHOT_MAX_FILES))
    for (raw in archiveEntries.take(LOCAL_SNAPSHOT_MAX_FILES)) {
        val entry = normalize(raw)
        if (entry.isEmpty() || entry.endsWith("/") || entry.contains('\n')) continue
        if (isExcluded(entry.substringAfterLast('/'))) continue
        archiveFiles.add(entry)
    }

    val existing = HashSet<String>(existingRelativePaths.size)
    for (raw in existingRelativePaths) {
        val entry = normalize(raw)
        if (entry.isEmpty() || entry.endsWith("/") || entry.contains('\n')) continue
        existing.add(entry)
    }

    return archiveFiles.intersect(existing).sorted()
}

/**
 * 只有「用户主动选两份都留 + 干净恢复关闭 + 确实有冲突旧文件」才生成快照。
 * 干净恢复会先清空目录，此时策略静默失效，不该平白多出一份快照。
 */
fun shouldCreateLocalSnapshot(
    strategy: RestoreConflictStrategy,
    cleanRestoring: Boolean,
    snapshotPaths: List<String>,
): Boolean = cleanRestoring.not()
        && strategy == RestoreConflictStrategy.KEEP_BOTH
        && snapshotPaths.isNotEmpty()
