package com.xayah.core.service.util

import com.xayah.core.rootservice.service.RemoteRootService

/**
 * 备份完整性校验（安全底线）：对每个归档文件计算 MD5，恢复前校验，防止恢复损坏的数据包。
 *
 * 采用 MD5（而非 SHA-256）是刻意选择：目标是防「意外损坏」（SD 卡坏块、传输丢包、bit rot），
 * 不是防恶意篡改。MD5 读完整份文件、对每个字节敏感，能覆盖 `tar -tf` 只校验头部块的盲区，
 * 且项目已有 root 侧 MD5 接口，复用成本最低。
 */
object ChecksumUtil {
    /**
     * 备份完成后：计算归档 [src] 的 MD5，写到归档旁边的 `[src].md5` 文件。
     * 返回算出的 MD5；计算失败返回 null（不阻断备份主流程）。
     */
    suspend fun write(rootService: RemoteRootService, src: String): String? =
        rootService.calculateMD5(src)?.also { md5 ->
            rootService.writeText(md5, "$src.md5")
        }

    /**
     * 恢复前：读取归档旁边的 `[src].md5`，重算当前文件的 MD5 并比对。
     *
     * 返回值语义：
     * - null：校验通过，或无需校验（无 .md5 / 校验值为空 / 算不出 MD5），可正常恢复。
     * - 非 null：[ChecksumMismatch]，MD5 不一致，调用方应提示用户决定是否强制恢复。
     */
    suspend fun verify(rootService: RemoteRootService, src: String): ChecksumMismatch? {
        val md5File = "$src.md5"
        if (rootService.exists(md5File).not()) return null

        val expected = rootService.readText(md5File).trim()
        if (expected.isEmpty()) return null

        val actual = rootService.calculateMD5(src) ?: return null
        if (actual.isEmpty()) return null

        return if (expected.equals(actual, ignoreCase = true)) {
            null
        } else {
            ChecksumMismatch(archivePath = src, expected = expected, actual = actual)
        }
    }
}
