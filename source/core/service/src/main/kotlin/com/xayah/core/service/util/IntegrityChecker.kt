package com.xayah.core.service.util

import com.xayah.core.model.LZ4_SUFFIX
import com.xayah.core.model.TAR_SUFFIX
import com.xayah.core.model.ZSTD_SUFFIX
import com.xayah.core.rootservice.service.RemoteRootService

/**
 * 备份完整性检查：恢复前扫描应用的备份目录，比对 `.md5` 文件与归档本体是否成对。
 *
 * 背景：出现过「.md5 还在、tar 本体丢失」的情况（微信/QQ 等大文件备份在打包/传输/解压环节丢失），
 * 恢复时 `exists(src)` 为 false 直接报 "Not exist" 失败，用户事先毫不知情。
 * 本检查在恢复开始前把缺失项列出来提示用户。
 *
 * 规则：
 * 1. 目录内每个 `*.md5` 文件对应的归档本体必须存在（.md5 是备份成功写入的最后一步，有 md5 无本体 = 数据丢失）；
 * 2. 目录内完全没有归档文件（也无 .md5）视为空目录，整个应用无法恢复；
 * 3. 只有归档、没有 .md5 不视为缺失（旧版本备份可能没有 md5，恢复时跳过校验即可）。
 */
data class IntegrityIssue(
    val label: String,
    val packageName: String,
    val missingFiles: List<String>,
) {
    /** true = 目录里没有任何归档文件（整个应用缺失） */
    val isEmpty: Boolean get() = missingFiles.isEmpty()
}

data class IntegrityReport(
    val issues: List<IntegrityIssue>,
) {
    val isEmpty: Boolean get() = issues.isEmpty()

    /** 生成缺失列表正文（UI 弹窗使用），每行一个应用。 */
    fun formatMessage(): String = buildString {
        issues.forEach { issue ->
            if (issue.isEmpty) {
                append("· ${issue.label}（${issue.packageName}）：无备份文件\n")
            } else {
                append("· ${issue.label}（${issue.packageName}）：${issue.missingFiles.joinToString("、")}\n")
            }
        }
    }
}

object IntegrityChecker {
    private val ARCHIVE_EXTENSIONS = listOf(TAR_SUFFIX, ZSTD_SUFFIX, LZ4_SUFFIX)

    /**
     * 扫描单个应用备份目录，返回缺失项；目录完整返回 null。
     */
    suspend fun checkDir(
        rootService: RemoteRootService,
        srcDir: String,
        label: String,
        packageName: String,
    ): IntegrityIssue? {
        // 目录不存在：整个应用缺失
        if (rootService.exists(srcDir).not()) {
            return IntegrityIssue(label = label, packageName = packageName, missingFiles = emptyList())
        }

        val files = rootService.listFilePaths(path = srcDir, listFiles = true, listDirs = false)
        val archiveFiles = files.filter { f -> ARCHIVE_EXTENSIONS.any { f.endsWith(it) } }
        val md5Files = files.filter { it.endsWith(".md5") }

        // 没有任何归档文件（也无 md5）：空目录，无法恢复
        if (archiveFiles.isEmpty() && md5Files.isEmpty()) {
            return IntegrityIssue(label = label, packageName = packageName, missingFiles = emptyList())
        }

        // md5 存在但归档本体不存在：缺失
        val missing = md5Files.mapNotNull { md5 ->
            val archive = md5.removeSuffix(".md5")
            if (rootService.exists(archive)) null else archive.substringAfterLast('/')
        }
        return if (missing.isNotEmpty()) {
            IntegrityIssue(label = label, packageName = packageName, missingFiles = missing)
        } else {
            null
        }
    }
}
