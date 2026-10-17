package com.xayah.core.util.command

/**
 * 目标存储的硬性上限校验。
 *
 * 场景来源：用户把备份目录放在 FAT32 的 SD 卡上，微信 user 数据 8 GB，zstd 写到 4 GiB 时
 * 报 `Write error : cannot write block : File too large`（退出码 70），随后完整性校验读到
 * `premature end`，归档被判损坏并删除——白等几分钟，且这一次备份里没有该类数据。
 *
 * FAT32 的单文件上限是 4 GiB，且无法通过分卷规避（本地备份没有分卷）。
 */
object StorageLimit {
    /** FAT32 单文件硬上限 */
    const val FAT32_MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024

    /**
     * 判定用的**源数据**阈值，取 6 GiB 而不是 4 GiB。
     *
     * 归档是压缩后的产物，拿源大小和 4 GiB 直接比会把「源 5 GB、压完 2.5 GB」这类本可成功的
     * 备份也拦掉（zstd -1 对 apk/dex 能压到一半）。6 GiB 的意义是：即使按最乐观的 33% 压缩率
     * 算，产物也已经逼近 4 GiB，属于几乎必然失败；而 4~6 GiB 的源数据放行去试，
     * 真写不下还有 [isTooLargeError] 兜底给准确原因。
     */
    private const val BLOCK_SOURCE_BYTES = 6L * 1024 * 1024 * 1024

    /**
     * 是否应当在打包前跳过。
     *
     * 只在「预估源大小已经逼近上限」时才探测文件系统类型——探测要跑两次 shell，
     * 普通应用用不上，不该给每次备份都加上这份开销。
     *
     * @param estimatedBytes 源数据未压缩大小；<= 0 表示未知，一律放行（交给失败兜底）。
     */
    suspend fun isBlockedByFat32(dstDir: String, estimatedBytes: Long): Boolean {
        if (estimatedBytes <= BLOCK_SOURCE_BYTES) return false
        val fsType = PreparationUtil.getStorageFileSystemType(dstDir).lowercase()
        if (fsType.isEmpty()) return false
        // exfat 无单文件上限；只有 vfat/msdos/fat 这类才是 FAT32
        if (fsType.contains("exfat") || fsType.contains("fat").not()) return false
        return true
    }

    /** 打包失败后的兜底判断：即使预检没拦住，也要把 zstd/tar 的原始报错翻译成人能看懂的原因。 */
    fun isTooLargeError(code: Int, output: List<String>): Boolean =
        code == 70 || output.any { it.contains("File too large", ignoreCase = true) }

    /** 人类可读的 GB 数值，用于提示文案 */
    fun formatGb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
