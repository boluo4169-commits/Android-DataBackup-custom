package com.xayah.core.service.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 增量清单（manifest）序列化/解析/比对的纯逻辑单测。
 * 正确性命门：解析失败必须返回 null（调用方按「已变化」走全量），绝不能半解析出假相等。
 */
class IncrementalBackupUtilTest {

    private val params = IncrementalBackupUtil.ArchiveParams(
        compressionType = "TAR_ZSTD",
        level = 1,
        threads = 2,
        followSymlinks = false,
        exclusionList = listOf("com.app/cache", "Backup_*", ".trashed-*"),
    )

    @Test
    fun `serialize-parse round trip preserves hash and params`() {
        val hash = "a".repeat(64)
        val parsed = IncrementalBackupUtil.parse(IncrementalBackupUtil.serialize(hash, params))

        assertEquals(hash to params, parsed)
    }

    @Test
    fun `exclusion separator round trip`() {
        val withEmpty = params.copy(exclusionList = listOf("a", "b"))
        val (_, parsed) = IncrementalBackupUtil.parse(IncrementalBackupUtil.serialize("b".repeat(64), withEmpty))!!

        assertEquals(listOf("a", "b"), parsed.exclusionList)
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(IncrementalBackupUtil.parse(""))
        assertNull(IncrementalBackupUtil.parse("not a manifest"))
        assertNull(IncrementalBackupUtil.parse("v=2\nhash=${"c".repeat(64)}"))
    }

    @Test
    fun `parse rejects missing keys`() {
        val full = IncrementalBackupUtil.serialize("d".repeat(64), params)
        // 逐行抽掉关键字段，每种缺失都必须判 null
        listOf("hash=", "ct=", "level=", "threads=").forEach { prefix ->
            val broken = full.lines().filterNot { it.startsWith(prefix) }.joinToString("\n")
            assertNull("missing '$prefix' must not parse", IncrementalBackupUtil.parse(broken))
        }
    }

    @Test
    fun `params equality is value based`() {
        assertEquals(params, params.copy())
        assert(params != params.copy(level = 3))
        assert(params != params.copy(followSymlinks = true))
        assert(params != params.copy(exclusionList = emptyList()))
    }

    @Test
    fun `extractHash anchors 64 hex chars`() {
        val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(hash, IncrementalBackupUtil.extractHash(listOf("$hash  -")))
        // stderr 合流进来的干扰行不影响提取
        assertEquals(hash, IncrementalBackupUtil.extractHash(listOf("find: permission denied", "$hash  -")))
        assertNull(IncrementalBackupUtil.extractHash(listOf("find: permission denied")))
        // 不满 64 位不算数
        assertNull(IncrementalBackupUtil.extractHash(listOf("${hash.dropLast(1)}  -")))
    }
}
