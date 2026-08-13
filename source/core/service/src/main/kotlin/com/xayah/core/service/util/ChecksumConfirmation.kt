package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 归档 MD5 校验失败时的提示信息。
 */
data class ChecksumMismatch(
    val archivePath: String,
    val expected: String,
    val actual: String,
)

/**
 * 校验失败时的「是否强制恢复」确认通道。
 *
 * 恢复任务跑在后台 Service 的 IO 协程里，无法直接弹 UI；本单例充当跨层信号：
 * 1. service 层校验失败时调用 [awaitDecision]，挂起等待用户决定；
 * 2. UI 层观察 [request]，弹确认框，用户选择后调用 [decide] 回传决定；
 * 3. [awaitDecision] 恢复，返回 true（强制继续）或 false（取消）。
 *
 * 「多次变一次」：一次恢复操作里，第一次校验失败会弹窗询问；用户选择后，本次任务内
 * 后续的校验失败直接沿用该选择（[rememberedChoice]），不再逐个归档弹窗。
 * 每次恢复任务开始时调用 [reset] 清空记忆。
 */
object ChecksumConfirmation {
    private val _request = MutableStateFlow<ChecksumMismatch?>(null)
    val request: StateFlow<ChecksumMismatch?> = _request

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var rememberedChoice: Boolean? = null

    /**
     * service 层调用：挂起等待用户决定。返回 true = 强制恢复，false = 取消。
     * 若本次任务已做过选择，则直接沿用、不再弹窗。
     */
    suspend fun awaitDecision(mismatch: ChecksumMismatch): Boolean {
        rememberedChoice?.let { return it }
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _request.value = mismatch
        return d.await()
    }

    /**
     * UI 层调用：回传用户决定。[forceRestore] = true 表示强制继续恢复。
     */
    fun decide(forceRestore: Boolean) {
        _request.value = null
        rememberedChoice = forceRestore
        deferred?.complete(forceRestore)
        deferred = null
    }

    /**
     * 每次恢复任务开始时调用，清空记忆，让下一次恢复重新询问。
     */
    fun reset() {
        rememberedChoice = null
    }
}
