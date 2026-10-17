package com.xayah.core.util.command

import com.xayah.core.util.SymbolUtil.shellQuote

/**
 * 目标存储的硬性上限校验。
 *
 * 场景来源：用户把备份目录放在 FAT32 的 SD 卡上，微信 user 数据 8 GB，zstd 写到 4 GiB 时
 * 报 `Write error : cannot write block : File too large`（退出码 70），随后完整性校验读到
 * `premature end`，归档被判损坏并删除——白等几分钟，且这一次备份里没有该类数据。
 *
 * FAT32 的单文件上限是 4 GiB，且无法通过分卷规避（本地备份没有分卷）。所以在打包前拦下，
 * 比打包三分钟后失败要有价值得多。
 */
object StorageLimit {
    private const val FAT32_MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
    // 元数据与 zstd 帧开销要留余量，压到 4 GiB 整一样会失败
    private const val SAFETY_MARGIN_BYTES = 64L * 1024 * 1024

    /**
     * 返回拦截原因；null 表示可以继续。
     *
     * 只在「预估大小已经超过 4 GiB」时才去探测文件系统类型——探测要跑两次 shell，
     * 普通应用用不上，不该给每次备份都加上这份开销。
     *
     * @param estimatedBytes 源数据未压缩大小；压缩后一般更小，但已压缩过的数据（聊天记录里的
     *   图片视频）压缩率接近 1，用原始大小做保守判断是合适的。传 0 或负数表示未知，直接放行。
     */
    suspend fun fat32BlockReason(dstDir: String, estimatedBytes: Long): String? {
        if (estimatedBytes <= 0) return null
        if (estimatedBytes <= FAT32_MAX_FILE_BYTES - SAFETY_MARGIN_BYTES) return null
        val fsType = PreparationUtil.getStorageFileSystemType(dstDir).lowercase()
        if (fsType.isEmpty()) return null
        // exfat 无单文件上限；只有 vfat/msdos/fat 这类才是 FAT32
        if (fsType.contains("exfat") || fsType.contains("fat").not()) return null
        val estimatedGb = estimatedBytes / 1024.0 / 1024.0 / 1024.0
        return "The target partition is FAT32, which cannot hold a single file larger than 4 GiB. " +
            "This item is about ${"%.1f".format(estimatedGb)} GB. Use an exFAT-formatted card, " +
            "internal storage, or cloud backup instead."
    }

    /** 打包失败后的兜底判断：即使预检没拦住，也要把 zstd/tar 的原始报错翻译成人能看懂的原因。 */
    fun isTooLargeError(code: Int, output: List<String>): Boolean =
        code == 70 || output.any { it.contains("File too large", ignoreCase = true) }

    const val TOO_LARGE_HINT = "The archive was cut off because the target partition cannot hold a single " +
        "file larger than 4 GiB (FAT32). Switch to an exFAT card, internal storage, or cloud backup."
}
