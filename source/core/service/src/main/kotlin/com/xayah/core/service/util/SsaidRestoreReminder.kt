package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 恢复时 SSAID（Android ID）相关提醒的跨层信号。
 *
 * 恢复任务跑在后台 Service 的 IO 协程里，无法直接弹 UI。本单例用于在恢复开始前，
 * 当用户开启了「恢复 Android id」或「随机化 Android id」时，向 UI 层请求弹确认框：
 * 1. service 层调用 [awaitConfirm]，挂起等待用户确认；
 * 2. UI 层观察 [message]，弹确认框，用户选择后调用 [decide] 回传；
 * 3. [awaitConfirm] 返回 true（继续恢复）或 false（用户取消）。
 */
object SsaidRestoreReminder {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * service 层调用：挂起等待用户确认。返回 true = 继续，false = 取消。
     */
    suspend fun awaitConfirm(message: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _message.value = message
        return d.await()
    }

    /**
     * UI 层调用：回传用户决定。[confirmed] = true 表示继续恢复。
     */
    fun decide(confirmed: Boolean) {
        _message.value = null
        deferred?.complete(confirmed)
        deferred = null
    }
}
