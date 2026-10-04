package com.xayah.core.util.command

import com.xayah.core.common.util.toSpaceString
import com.xayah.core.util.LogUtil
import com.xayah.core.util.TrashedFilePatterns
import com.xayah.core.util.SymbolUtil.shellQuote
import com.xayah.core.util.model.ShellResult

/**
 * 统计那些「存在但不可见」的文件 —— 媒体库回收站（`.trashed-`）与写入中（`.pending-`）。
 *
 * 光打匹配模式（[TrashedFilePatterns]）用户看不出到底跳过了什么；而一个相册目录实测
 * 能有几百个 `.trashed-*`，只报个数字等于没说。这里把命中项捞出来，样例限量输出到日志。
 */
object TrashedUtil {
    /**
     * 模式形态固定是 `<前缀>-*`，所以直接拿前缀做 startsWith 比较 —— 比走 glob 匹配器快一个量级，
     * 而扫描要跑在成千上万个文件名上。
     */
    private val prefixes = TrashedFilePatterns.map { it.removeSuffix("*") }

    fun isTrashed(fileName: String): Boolean = prefixes.any { fileName.startsWith(it) }

    /**
     * 从一组路径中挑出命中项。
     * @return 命中总数 to 前 [limit] 个样例（只取 basename，免得日志里全是长路径）
     */
    fun summarize(paths: List<String>, limit: Int = 5): Pair<Int, List<String>> {
        val hit = paths.filter { isTrashed(it.substringAfterLast('/')) }
        return hit.size to hit.take(limit).map { it.substringAfterLast('/') }
    }

    /**
     * 备份侧：文件此刻还躺在源目录里，用 find 直接捞。
     * 与 tar 侧的 `--exclude` 同源（[TrashedFilePatterns]），保证"报的就是被跳过的"。
     */
    suspend fun scanDir(src: String): List<String> = runCatching {
        // 括号必须 shellQuote：BaseUtil 是空格拼接参数，裸 '(' 会被 shell 当语法符号
        // （实测报 `syntax error: unexpected '('`）。
        val args = mutableListOf("find", shellQuote(src), "-type", "f", shellQuote("("))
        TrashedFilePatterns.forEachIndexed { index, pattern ->
            if (index > 0) args += "-o"
            args += "-name"
            args += shellQuote(pattern)
        }
        args += shellQuote(")")
        BaseUtil.execute(*args.toTypedArray()).out
    }.getOrDefault(emptyList())

    /**
     * 恢复侧：被跳过的文件不会落到目标目录，只能从归档清单里找。
     */
    suspend fun scanArchive(archive: String): List<String> = runCatching {
        Tar.list(archive).out
    }.getOrDefault(emptyList())

    /** 没有命中就不打日志 —— 绝大多数备份里一个都没有，别刷无意义的空行。 */
    suspend fun logExcluded(scene: String, paths: List<String>) {
        val (total, samples) = summarize(paths)
        if (total == 0) return
        val suffix = if (total > samples.size) ", ..." else ""
        LogUtil.log { "TrashedUtil" to "Excluded $total trashed/pending file(s)$scene: ${samples.toSpaceString()}$suffix" }
    }
}
