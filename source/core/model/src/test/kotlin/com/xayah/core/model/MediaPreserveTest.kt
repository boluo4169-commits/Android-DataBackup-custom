package com.xayah.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreserveTest {

    @Test
    fun emptyAndMainOnly_neverExpires() {
        assertTrue(pickExpiredPreserves(emptyList(), 2).isEmpty())
        assertTrue(pickExpiredPreserves(listOf(0L), 2).isEmpty())
        assertTrue(pickExpiredPreserves(listOf(0L, 0L), 2).isEmpty())
    }

    @Test
    fun underLimit_keepsAll() {
        val ids = listOf(20261110120000L, 20261110110000L)
        assertTrue(pickExpiredPreserves(ids, 2).isEmpty())
        assertTrue(pickExpiredPreserves(ids, 5).isEmpty())
    }

    @Test
    fun overLimit_dropsOldestOnly() {
        val newest = 20261110140000L
        val mid = 20261110130000L
        val old = 20261110120000L
        val oldest = 20261110110000L
        // 输入故意乱序，且混入主版本与重复 id
        val ids = listOf(0L, mid, oldest, newest, old, mid)
        assertEquals(listOf(old, oldest), pickExpiredPreserves(ids, 2))
        assertEquals(listOf(oldest), pickExpiredPreserves(ids, 3))
        assertEquals(listOf(mid, old, oldest), pickExpiredPreserves(ids, 1))
    }

    @Test
    fun invalidMaxCount_alwaysKeepsAtLeastOne() {
        val ids = listOf(20261110130000L, 20261110120000L, 20261110110000L)
        assertEquals(listOf(20261110120000L, 20261110110000L), pickExpiredPreserves(ids, 0))
        assertEquals(listOf(20261110120000L, 20261110110000L), pickExpiredPreserves(ids, -7))
    }

    @Test
    fun mixedTimestampFormats_sortByNumericValue() {
        // 13 位毫秒戳（~1.8e12）比 14 位日期串（~2.0e13）小一个数量级，降序时排在最后。
        // 这就是 Plan 第 4 条「两种格式混用会串序」的由来；展示层由 Phase 0 兼容。
        val millis = 1762778400000L
        val date = 20261110120000L
        assertEquals(listOf(millis), pickExpiredPreserves(listOf(date, millis), 1))
    }

    @Test
    fun sanitize_clampsToSliderRange() {
        assertEquals(1, sanitizeMaxPreserveMediaCount(0))
        assertEquals(1, sanitizeMaxPreserveMediaCount(-3))
        assertEquals(2, sanitizeMaxPreserveMediaCount(2))
        assertEquals(5, sanitizeMaxPreserveMediaCount(99))
    }
}
