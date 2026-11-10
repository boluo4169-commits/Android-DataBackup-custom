package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.xayah.core.model.ConflictPreview
import com.xayah.core.model.RestoreConflictStrategy

/** 恢复前预览弹窗要展示的一次请求。 */
data class RestorePreviewRequest(
    val name: String,
    val preview: ConflictPreview,
    val strategy: RestoreConflictStrategy,
    val cleanRestoring: Boolean,
)

/**
 * 恢复前冲突预览的跨层信号（Service → 处理页 UI）。
 *
 * 与 [ChecksumConfirmation] 同款「Service 挂起 → UI 弹窗 → 用户决定 → Service 继续」机制：
 * 1. `MediumRestoreUtil` 解压前调用 [awaitConfirm]，挂起等待；
 * 2. `PageProcessing` 观察 [request] 弹清单，用户点确认 / 取消后调用 [decide]；
 * 3. 取消则该目录不解压（记为跳过），后续目录沿用同一次决定，不会反复弹窗。
 *
 * 「多次变一次」：一次恢复任务里第一个有冲突的目录弹窗，用户的选择记在 [rememberedChoice]，
 * 后续目录直接沿用；任务开始时（恢复 Service 的 `onProcessing`）必须调用 [reset]。
 * [total] 累加本次任务所有目录的统计，供完成页展示逐项结果。
 */
object RestorePreviewConfirmation {
    private val _request = MutableStateFlow<RestorePreviewRequest?>(null)
    val request: StateFlow<RestorePreviewRequest?> = _request

    private val _total = MutableStateFlow(ConflictPreview())
    val total: StateFlow<ConflictPreview> = _total

    private val _skippedDirs = MutableStateFlow(0)
    /** 因用户在预览处取消而跳过的目录数。 */
    val skippedDirs: StateFlow<Int> = _skippedDirs

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var rememberedChoice: Boolean? = null

    fun reset() {
        rememberedChoice = null
        deferred?.cancel()
        deferred = null
        _request.value = null
        _total.value = ConflictPreview()
        _skippedDirs.value = 0
    }

    /** 本次任务是否已有人点过「取消」。 */
    fun isCancelled(): Boolean = rememberedChoice == false

    fun markSkipped() {
        _skippedDirs.value = _skippedDirs.value + 1
    }

    /** 累加统计（每个目录都调，无论是否弹窗），供完成页汇总。 */
    fun accumulate(preview: ConflictPreview) {
        _total.value = _total.value + preview
    }

    /**
     * 弹出预览并等待。若本次任务已做过选择则直接沿用。
     * 返回 true = 继续解压，false = 取消（跳过该目录）。
     */
    suspend fun awaitConfirm(request: RestorePreviewRequest): Boolean {
        rememberedChoice?.let { return it }
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _request.value = request
        return d.await()
    }

    /** UI 层回传用户决定，并记住，本次任务内不再弹窗。 */
    fun decide(confirmed: Boolean) {
        rememberedChoice = confirmed
        _request.value = null
        deferred?.complete(confirmed)
        deferred = null
    }
}
