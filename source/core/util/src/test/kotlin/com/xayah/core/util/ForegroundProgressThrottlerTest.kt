package com.xayah.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描时的前台进度通知节流。
 *
 * 背景：逐包回调 setForeground 会在**主线程**触发 NotificationManager.notify。
 * 2026-09-14 在 OPD2413（Android 16 / 47 个第三方应用）实测：扫描期间 0.79 秒内
 * 产生 **291 次 notify，全部落在主线程**（速率约 366 次/秒），主线程被连续占满，
 * 而扫描本身只用了 145 ms——通知开销是扫描工作量的 5 倍以上。
 *
 * 节流后同一场景降到 3 次。这里锁住节流器的三条行为契约：
 * 首条必发、间隔内丢弃、末条必发。
 *
 * 时钟通过构造参数注入，使本测试可在纯 JVM 下运行（不触碰 SystemClock）。
 */
class ForegroundProgressThrottlerTest {
    private var nowMs = 0L

    private fun throttler(intervalMs: Long = 250L) = ForegroundProgressThrottler(intervalMs) { nowMs }

    @Test
    fun `first progress always emits`() {
        val t = throttler()
        // 时间源起点为 0 也必须发出：早期实现用 0 作哨兵，首条会被误吞
        assertTrue(t.shouldEmit(cur = 0, max = 47))
    }

    @Test
    fun `progress within interval is dropped`() {
        val t = throttler(intervalMs = 250L)
        assertTrue(t.shouldEmit(cur = 0, max = 47))

        nowMs += 100
        assertFalse(t.shouldEmit(cur = 1, max = 47))
        assertFalse(t.shouldEmit(cur = 2, max = 47))

        nowMs += 100
        assertFalse(t.shouldEmit(cur = 3, max = 47))
    }

    @Test
    fun `progress after interval emits again`() {
        val t = throttler(intervalMs = 250L)
        assertTrue(t.shouldEmit(cur = 0, max = 47))

        nowMs += 250
        assertTrue(t.shouldEmit(cur = 1, max = 47))

        // 紧跟着的下一帧仍应被丢弃，直到下一个间隔
        nowMs += 10
        assertFalse(t.shouldEmit(cur = 2, max = 47))
    }

    @Test
    fun `last progress always emits even within interval`() {
        val t = throttler(intervalMs = 250L)
        assertTrue(t.shouldEmit(cur = 0, max = 47))

        // 距上一条只过了 1ms，但这是最后一条：必须发出，否则通知会停在旧进度上
        nowMs += 1
        assertTrue(t.shouldEmit(cur = 46, max = 47))
    }

    @Test
    fun `zero based tail is detected`() {
        val t = throttler(intervalMs = 250L)
        assertTrue(t.shouldEmit(cur = 0, max = 1))
        // 单元素场景：cur=0 即 max-1，同时也已经是首条

        val t2 = throttler(intervalMs = 250L)
        t2.shouldEmit(cur = 0, max = 10)
        nowMs += 1
        // Repo 用 forEachIndexed 回调，cur 是 0-based，末尾是 max-1 而不是 max
        assertTrue(t2.shouldEmit(cur = 9, max = 10))
    }

    @Test
    fun `empty range does not count as last`() {
        val t = throttler(intervalMs = 250L)
        assertTrue(t.shouldEmit(cur = 0, max = 0))

        // max=0 时不应该被当成「末条」而绕过节流
        nowMs += 1
        assertFalse(t.shouldEmit(cur = 0, max = 0))
    }

    @Test
    fun `simulated scan collapses to a handful of emits`() {
        val t = throttler(intervalMs = 250L)
        var emitted = 0

        // 模拟实测场景：47 个应用在 145ms 内全部回调完
        for (cur in 0 until 47) {
            nowMs += 3
            if (t.shouldEmit(cur = cur, max = 47)) emitted++
        }

        // 首条 + 末条；中间被 250ms 间隔吃掉。实测同场景为 3 次
        assertTrue("emitted=$emitted", emitted in 2..3)
    }
}
