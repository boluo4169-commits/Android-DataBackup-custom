package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 云端分卷的命名与顺序约定。
 *
 * 背景：归档超过阈值时用 `split -b <N> -a 3` 切成 `<归档>.part.aaa/.aab/…`，恢复时按序
 * `cat` 拼回。**顺序错一位，合并出来的归档就是坏的**（md5 会挂、解压会炸），所以这里锁住
 * 三件事：只认本归档的分卷、按字典序（== 字节顺序）排序、卷大小可累加。
 *
 * split / merge 本体依赖设备上的 toybox，纯 JVM 跑不了 —— 那部分在真机实测过
 * （2026-09-18：5 MiB 文件切 2 MiB/卷 → aaa/aab/aac，cat 拼回后 cmp 逐字节一致）。
 */
class SplitUtilTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val archive = "user_0.tar.zst"

    @Test
    fun `isPartOf only matches its own volumes`() {
        assertTrue(SplitUtil.isPartOf("$archive.part.aaa", archive))
        assertTrue(SplitUtil.isPartOf("$archive.part.zzz", archive))
        // 同目录下的整档、md5、别的归档，以及"看起来像"的都要排除
        assertFalse(SplitUtil.isPartOf(archive, archive))
        assertFalse(SplitUtil.isPartOf("$archive.md5", archive))
        assertFalse(SplitUtil.isPartOf("other.tar.zst.part.aaa", archive))
        assertFalse(SplitUtil.isPartOf("$archive.part", archive))
    }

    @Test
    fun `listParts returns volumes in byte order`() {
        val dir = folder.newFolder()
        // 故意乱序创建；同目录再放入不该被选中的文件
        listOf("aaa", "aab", "aac", "aaz", "aba").reversed().forEach {
            File(dir, "$archive.part.$it").writeText(it)
        }
        File(dir, archive).writeText("archive")
        File(dir, "$archive.md5").writeText("md5")
        File(dir, "other.tar.zst.part.aaa").writeText("other")

        val parts = SplitUtil.listParts(File(dir, archive).path)

        assertEquals(
            listOf("aaa", "aab", "aac", "aaz", "aba").map { File(dir, "$archive.part.$it").path },
            parts,
        )
    }

    @Test
    fun `listParts tolerates a missing directory`() {
        assertEquals(emptyList<String>(), SplitUtil.listParts(File(folder.root, "nope").path + File.separator + archive))
    }

    @Test
    fun `totalBytes sums volume sizes`() {
        val dir = folder.newFolder()
        File(dir, "$archive.part.aaa").writeBytes(ByteArray(3))
        File(dir, "$archive.part.aab").writeBytes(ByteArray(2))

        val parts = SplitUtil.listParts(File(dir, archive).path)

        assertEquals(5L, SplitUtil.totalBytes(parts))
    }
}
