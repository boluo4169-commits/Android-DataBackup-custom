package com.xayah.core.util

import android.os.Looper
import android.os.SystemClock

/**
 * 主线程卡顿检测 + 堆栈采样（BlockCanary 原理）。
 * 用 Looper 监控每个 Message 的处理时长，卡顿超阈值时由看门狗线程 dump 主线程堆栈，
 * 从而定位「主线程被阻塞的 4~5 秒到底在干什么」。
 */
object UiPerformanceMonitor {
    private const val FREEZE_THRESHOLD_MS = 500L
    private const val WATCHDOG_INTERVAL_MS = 100L
    private var installed = false

    @Volatile
    private var dispatchingStartTime = 0L

    fun install() {
        if (installed) return
        installed = true

        val mainThread = Looper.getMainLooper().thread

        Looper.getMainLooper().setMessageLogging { msg ->
            if (msg?.startsWith(">>>>> Dispatching") == true) {
                dispatchingStartTime = SystemClock.elapsedRealtime()
            } else if (msg?.startsWith("<<<<< Finished") == true) {
                dispatchingStartTime = 0L
            }
        }

        Thread {
            var lastDumpTime = 0L
            while (true) {
                Thread.sleep(WATCHDOG_INTERVAL_MS)
                val start = dispatchingStartTime
                if (start > 0) {
                    val cost = SystemClock.elapsedRealtime() - start
                    if (cost > FREEZE_THRESHOLD_MS) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastDumpTime > 1000) {
                            lastDumpTime = now
                            val stack = mainThread.stackTrace.joinToString("\n") { "    at $it" }
                            LogUtil.log("UI freeze: main thread blocked ${cost}ms\n$stack")
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "UiFreezeWatchdog"
        }.start()
    }
}
