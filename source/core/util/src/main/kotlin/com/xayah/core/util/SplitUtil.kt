package com.xayah.core.util

import com.xayah.core.util.command.BaseUtil
import com.xayah.core.util.model.ShellResult
import java.io.File

/**
 * 云端分卷：归档超过阈值时切成多卷上传（绕开网盘/云盘的单文件大小限制），恢复时按序合并。
 *
 * 命名：`<归档名>.part.aaa` / `.aab` / …（toybox `split -a 3`，字母后缀；字典序 == 字节序）
 * 一致性：split 按输入顺序切、cat 按同名顺序拼回，结果与原始文件逐字节一致
 * （2026-09-18 真机实测 5 MiB 文件切 2 MiB/卷 → aaa/aab/aac，拼回后 cmp 一致）。
 *
 * 本地备份不受影响：始终保留完整归档，分卷只存在于「上传/下载的搬运过程」中。
 */
object SplitUtil {
    const val PART_INFIX = ".part."
    private const val SUFFIX_LEN = 3

    fun isPartOf(fileName: String, archiveName: String) =
        fileName.startsWith("$archiveName$PART_INFIX")

    /** 按 [bytes] 字节/卷切分 [src]，分卷落在同目录（名前缀 `<src>.part.`） */
    suspend fun split(src: String, bytes: Long): ShellResult = BaseUtil.execute(
        "split",
        "-b", bytes.toString(),
        "-a", SUFFIX_LEN.toString(),
        src,
        "$src$PART_INFIX",
        log = false,
    )

    /** 列出该归档在本地同目录下的分卷，按名字排序（== 按字节顺序） */
    fun listParts(archivePath: String): List<String> {
        val file = File(archivePath)
        val dir = file.parent ?: return emptyList()
        val name = file.name
        return File(dir).list()
            ?.filter { isPartOf(it, name) }
            ?.sorted()
            ?.map { File(dir, it).path }
            .orEmpty()
    }

    /**
     * 按顺序把分卷合并成 [dst]（逐字节还原），返回是否成功（长度等于各卷之和）。
     *
     * 用 Kotlin 流拼接、不走 `sh -c "cat … > dst"`：App 的 shell 包装会在命令尾部追加
     * `__RET=$?;echo <uuid>;echo <uuid> >&2;echo $__RET;unset __RET` 哨兵，命令行里的 `>`
     * 会把这个哨兵写进目标文件、`cat` 的真实输出反而丢失（2026-09-18 真机实测：
     * 1.73 GB 的合并结果只有 121 字节，内容就是哨兵文本）。
     */
    suspend fun merge(parts: List<String>, dst: String): Boolean = withIOContext {
        File(dst).outputStream().use { out ->
            parts.forEach { part ->
                File(part).inputStream().use { input ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE * 16)
                }
            }
            out.flush()
        }
        File(dst).length() == totalBytes(parts)
    }

    /** 合并所需的目标大小（各卷之和），用于进度计算 */
    fun totalBytes(parts: List<String>): Long = parts.sumOf { File(it).length() }
}
