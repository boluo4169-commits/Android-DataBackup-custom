package com.xayah.core.util

import android.view.Choreographer

/**
 * 主线程卡顿检测：用 Choreographer 监控帧间隔，主线程被阻塞超过阈值时记一条日志。
 * 用于定位「列表滑动卡顿」这类 UI 性能问题（此类问题不产生异常，只能靠帧间隔探测）。
 */
object UiPerformanceMonitor {
    private const val FREEZE_THRESHOLD_MS = 300L
    private var installed = false

    fun install() {
        if (installed) return
        installed = true

        var lastFrameTimeNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTimeNanos > 0) {
                    val diffMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000
                    if (diffMs > FREEZE_THRESHOLD_MS) {
                        LogUtil.log("UI freeze: main thread blocked ${diffMs}ms")
                    }
                }
                lastFrameTimeNanos = frameTimeNanos
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }
}
