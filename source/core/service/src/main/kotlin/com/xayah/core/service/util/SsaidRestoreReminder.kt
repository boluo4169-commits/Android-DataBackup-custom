package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 恢复时 SSAID（Android ID）相关提醒的跨层信号。
 *
 * 恢复任务跑在后台 Service 的 IO 协程里，无法直接弹 UI。本单例用于在恢复开始前，
 * 向 UI 层请求弹确认框。共用通道有两个场景，标题各不相同：
 * 1. 随机化/恢复 Android id 的确认；
 * 2. 同一应用勾选多个备份版本的覆盖提醒（v3.1.5）。
 * 历史坑：早期版本只有 message 一个槽，弹窗标题写死为「随机化Android ID」，
 * 多版本覆盖提醒套错标题——所以 title 必须随消息一起传递。
 *
 * 1. service 层调用 [awaitConfirm]，挂起等待用户确认；
 * 2. UI 层观察 [title] / [message]，弹确认框，用户选择后调用 [decide] 回传；
 * 3. [awaitConfirm] 返回 true（继续恢复）或 false（用户取消）。
 */
object SsaidRestoreReminder {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * service 层调用：挂起等待用户确认。返回 true = 继续，false = 取消。
     */
    suspend fun awaitConfirm(title: String, message: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _title.value = title
        _message.value = message
        return d.await()
    }

    /**
     * UI 层调用：回传用户决定。[confirmed] = true 表示继续恢复。
     */
    fun decide(confirmed: Boolean) {
        _message.value = null
        _title.value = null
        deferred?.complete(confirmed)
        deferred = null
    }
}
