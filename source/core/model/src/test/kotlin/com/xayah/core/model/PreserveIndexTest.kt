package com.xayah.core.model.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件历史版本的护盾序号。
 *
 * 文件侧「保护」只改 indexInfo.preserveId，lastBackupTime 整组保持原值，所以序号按
 * preserveId 降序而不是时间；历史已产生的 13 位毫秒戳与新格式 14 位日期串混用时，
 * 也要保证新版本排在前面（护盾 1）。
 */
class PreserveIndexTest {
    private fun item(id: Long, group: List<String>, preserveId: Long) = PreserveItem(id, group, preserveId)

    @Test
    fun empty_returnsEmpty() {
        assertTrue(buildPreserveIndexMap(emptyList()).isEmpty())
    }

    @Test
    fun mainVersion_isNotIndexed() {
        val map = buildPreserveIndexMap(listOf(item(1, listOf("DCIM", "/sdcard/DCIM"), 0L)))
        assertTrue(map.isEmpty())
    }

    @Test
    fun sameGroup_indexedByPreserveIdDesc() {
        val group = listOf("DCIM", "/sdcard/DCIM", "", "")
        val map = buildPreserveIndexMap(
            listOf(
                item(1, group, 0L),
                item(2, group, 20261110110000L),
                item(3, group, 20261110120000L),
            )
        )
        assertEquals(2, map.size)
        assertEquals(1, map[3])
        assertEquals(2, map[2])
    }

    @Test
    fun differentGroups_areIndependent() {
        val a = listOf("DCIM", "/sdcard/DCIM", "", "")
        val b = listOf("DCIM", "/sdcard/DCIM", "云端账号", "remote")
        val map = buildPreserveIndexMap(
            listOf(
                item(1, a, 20261110120000L),
                item(2, b, 20261110130000L),
                item(3, b, 20261110140000L),
            )
        )
        assertEquals(1, map[1])
        assertEquals(1, map[3])
        assertEquals(2, map[2])
    }

    @Test
    fun legacyMillisecondTimestamp_sortsAfterNewFormat() {
        val group = listOf("DCIM", "/sdcard/DCIM", "", "")
        // 旧数据：13 位毫秒戳（约 1.7e12）；新格式：14 位日期串（约 2.0e13）
        val map = buildPreserveIndexMap(
            listOf(
                item(1, group, 1762764000000L),
                item(2, group, 20261110120000L),
            )
        )
        assertEquals(1, map[2])
        assertEquals(2, map[1])
    }

    @Test
    fun duplicateIds_lastWins_andGroupsWithSeparatorAreNotMerged() {
        // groupKey 用 List 而非拼接字符串：路径里带 "|" 也不能把两组错误合并
        val a = listOf("A|B", "C")
        val b = listOf("A", "B|C")
        val map = buildPreserveIndexMap(
            listOf(
                item(1, a, 20261110120000L),
                item(2, b, 20261110130000L),
            )
        )
        assertEquals(1, map[1])
        assertEquals(1, map[2])
    }
}
