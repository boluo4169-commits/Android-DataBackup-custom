package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 备份完整性检查结果的确认通道（恢复前）。
 *
 * 恢复任务跑在后台 Service 的 IO 协程里，无法直接弹 UI；本单例充当跨层信号：
 * 1. service 层恢复前发现备份文件不完整时调用 [awaitDecision]，挂起等待用户决定；
 * 2. UI 层观察 [request]，弹窗列出缺失文件，用户选择后调用 [decide] 回传；
 * 3. [awaitDecision] 恢复，返回 true（继续恢复）或 false（取消）。
 */
object IntegrityConfirmation {
    private val _request = MutableStateFlow<IntegrityReport?>(null)
    val request: StateFlow<IntegrityReport?> = _request

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * service 层调用：挂起等待用户决定。返回 true = 继续恢复，false = 取消。
     */
    suspend fun awaitDecision(report: IntegrityReport): Boolean {
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _request.value = report
        return d.await()
    }

    /**
     * UI 层调用：回传用户决定。[continueRestore] = true 表示继续恢复。
     */
    fun decide(continueRestore: Boolean) {
        _request.value = null
        deferred?.complete(continueRestore)
        deferred = null
    }
}
