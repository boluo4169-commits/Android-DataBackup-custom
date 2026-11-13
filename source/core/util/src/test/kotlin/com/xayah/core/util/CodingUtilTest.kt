package com.xayah.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 路由参数编码契约：`URLEncoder` 的 `+` 表示空格与 Navigation 只解码 `%XX` 的行为冲突，
 * 曾导致账号名 `+cloud` 变成 ` cloud`、含 `%` 的名字在二次解码时直接崩溃。
 */
class CodingUtilTest {

    @Test
    fun spaceIsEncodedAsPercent20NotPlus() {
        assertEquals("%20", " ".encodeURL())
        assertEquals("%20", encodedURLWithSpace)
        assertEquals("a%20b", "a b".encodeURL())
    }

    @Test
    fun alreadyDecodedRouteArgsAreKeptVerbatim() {
        assertEquals("+cloud", "+cloud".decodeURL())
        // 孤立的 % 不能让 URLDecoder 抛异常
        assertEquals("100%DCIM", "100%DCIM".decodeURL())
        assertEquals("家", "家".decodeURL())
        assertEquals(" ", " ".decodeURL())
        assertEquals("/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM".decodeURL())
    }

    @Test
    fun roundTripAfterOneNavigationDecode() {
        listOf("家", "100%DCIM", "+cloud", "a b", "pad/files").forEach { raw ->
            val encoded = raw.encodeURL()
            // Navigation 对 path 参数只做一次 %XX 解码，用 URLDecoder 近似
            val decodedOnce = java.net.URLDecoder.decode(encoded, "UTF-8")
            assertEquals(raw, decodedOnce.decodeURL())
        }
    }
}
