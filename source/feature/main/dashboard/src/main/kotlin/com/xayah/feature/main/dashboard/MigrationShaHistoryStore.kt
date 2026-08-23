package com.xayah.feature.main.dashboard

import android.content.Context
import com.xayah.core.util.withIOContext
import java.io.File

/**
 * 迁移包导出校验码的历史记录（TSV 行存储：时间戳 \t 应用数 \t sha256）。
 * 记录仅存本机，用于同机重新导入或作为发包方快速回查。
 */
data class MigrationShaRecord(
    val time: Long,
    val apps: Int,
    val sha: String,
)

object MigrationShaHistoryStore {
    private const val MAX_RECORDS = 20

    private fun file(context: Context) = File(context.filesDir, "migration_sha_history.tsv")

    suspend fun load(context: Context): List<MigrationShaRecord> = withIOContext {
        runCatching {
            file(context).readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size != 3) return@mapNotNull null
                    val time = parts[0].toLongOrNull() ?: return@mapNotNull null
                    val apps = parts[1].toIntOrNull() ?: return@mapNotNull null
                    MigrationShaRecord(time = time, apps = apps, sha = parts[2])
                }
        }.getOrDefault(emptyList())
    }

    /** 新纪录插到最前，超出上限丢弃最旧的 */
    suspend fun append(context: Context, record: MigrationShaRecord) = withIOContext {
        runCatching {
            val all = (listOf(record) + load(context)).take(MAX_RECORDS)
            file(context).writeText(all.joinToString("\n") { "${it.time}\t${it.apps}\t${it.sha}" })
        }
    }
}
