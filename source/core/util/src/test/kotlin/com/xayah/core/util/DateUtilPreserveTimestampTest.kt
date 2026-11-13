package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * preserveId 的两种历史格式必须都能显示成可读时间。
 *
 * 应用侧 / 新的文件侧用 14 位 yyyyMMddHHmmss；文件侧「保护」在本次统一前用的是
 * 13 位毫秒时间戳。旧备份已经落盘，不能只支持新格式（会显示成 642 年后的日期）。
 */
class DateUtilPreserveTimestampTest {

    @Test
    fun fourteenDigits_parsedAsDateString() {
        assertEquals("2026-11-10 15:30:45", DateUtil.formatPreserveTimestamp(20261110153045L))
    }

    @Test
    fun twelveDigits_parsedAsLegacyMinutePrecision() {
        assertEquals("2026-11-10 15:30", DateUtil.formatPreserveTimestamp(202611101530L))
    }

    @Test
    fun thirteenDigits_parsedAsMilliseconds_notAsStringSlices() {
        val ms = 1762764000000L
        val result = DateUtil.formatPreserveTimestamp(ms)
        assertEquals(DateUtil.formatTimestamp(ms, DateUtil.PATTERN_YMD_HMS), result)
        // 不能被误当成 14 位日期串（会切出 "1762-76-40 …" 这类荒谬值）
        assertTrue(result.startsWith("202"))
    }

    @Test
    fun zero_isSafe() {
        assertEquals(DateUtil.formatTimestamp(0L, DateUtil.PATTERN_YMD_HMS), DateUtil.formatPreserveTimestamp(0L))
    }

    @Test
    fun nextPreserveTimestamp_rollsOverSecond59WithoutIllegalValue() {
        // 直接 +1 会得到 ...60（秒 60）这种非法时间，并与真实备份撞名。
        val next = DateUtil.nextPreserveTimestamp(20261112125959L)
        assertEquals(20261112130000L, next)
        assertEquals(14, next.toString().length)
        assertEquals("2026-11-12 13:00:00", DateUtil.formatPreserveTimestamp(next))
    }

    @Test
    fun nextPreserveTimestamp_fallsBackForLegacyLength() {
        assertEquals(1762764000001L, DateUtil.nextPreserveTimestamp(1762764000000L))
    }
}
