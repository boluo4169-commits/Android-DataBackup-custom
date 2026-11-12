package com.xayah.core.model

/**
 * 文件备份遇到「该目录已经有主备份」时的策略。
 *
 * 与恢复侧 [RestoreConflictStrategy] 是两套完全独立的偏好：一个决定备份怎么写入归档，
 * 另一个决定恢复时怎么对待本机已有文件，互不影响。
 *
 * 「跳过」指的是**整个目录已有主备份就不备份**，不是「同名文件跳过」；内容没变化时
 * 由增量备份自动跳过打包（`Data has not changed.`），与这里的选择无关。
 */
enum class MediaBackupStrategy(val id: Int) {
    /** 每次都用最新内容覆盖旧备份，占用最小，行为与 Phase 2 之前一致。 */
    REPLACE(0),

    /** 该目录已有主备份就跳过本次，保留已有的那份；首次备份照常执行。 */
    SKIP(1),

    /** 把旧主备份归档成历史版本（`@14位戳`），新的作为主版本，恢复时可任选。 */
    COPY(2),
    ;

    companion object {
        val DEFAULT = REPLACE

        fun fromId(id: Int): MediaBackupStrategy = entries.find { it.id == id } ?: DEFAULT
    }
}

/** 是否需要在重打前把旧主备份归档成历史版本：只有 COPY 且确实有主备份时才归档。 */
fun shouldArchiveMainBackup(strategy: MediaBackupStrategy, hasExistingMain: Boolean): Boolean =
    strategy == MediaBackupStrategy.COPY && hasExistingMain

/** 是否应整项跳过本次备份：只有 SKIP 且确实有主备份时才跳过。 */
fun shouldSkipExistingBackup(strategy: MediaBackupStrategy, hasExistingMain: Boolean): Boolean =
    strategy == MediaBackupStrategy.SKIP && hasExistingMain
