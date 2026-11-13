package com.xayah.core.service.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import com.xayah.core.model.ConflictPreview
import com.xayah.core.model.RestoreAskDecision
import com.xayah.core.model.RestoreConflictStrategy

/**
 * 弹窗前用户可能要看很长的冲突清单；超过该时长按「跳过整单」兜底，
 * 保证前台 Service 不会因为锁屏 / 系统 dismiss 弹窗 / 进程重建而永久挂起。
 */
private const val CONFIRM_TIMEOUT_MS = 300_000L

/** 恢复前预览弹窗要展示的一次请求。 */
data class RestorePreviewRequest(
    val name: String,
    val preview: ConflictPreview,
    val strategy: RestoreConflictStrategy,
    val cleanRestoring: Boolean,
)

/** 「逐个问」弹窗要展示的一次请求（策略 = ASK 时）。 */
data class RestoreAskRequest(
    val name: String,
    /** 中性预览：只用来展示冲突总数与新增数，不预设会被覆盖 / 跳过 / 留档。 */
    val preview: ConflictPreview,
    /** 本次任务上一次选的策略，作为弹窗默认项；首次为 OVERWRITE。 */
    val lastStrategy: RestoreConflictStrategy,
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
 *
 * ASK（「逐个问」）走 [askRequest] / [awaitStrategyDecision] / [decideStrategy] 这条独立通道：
 * 它与上面的预览确认**互斥**（同一时刻只会有一个生效），但共用 [total] / [skippedDirs] / [reset]，
 * 保证完成页统计与跳过计数不会因为两套机制而漏记。两条通道的「取消」互相可见：
 * 无论在哪一边点取消，本次任务剩余目录一律跳过。
 */
object RestorePreviewConfirmation {
    private val _request = MutableStateFlow<RestorePreviewRequest?>(null)
    val request: StateFlow<RestorePreviewRequest?> = _request

    private val _askRequest = MutableStateFlow<RestoreAskRequest?>(null)
    val askRequest: StateFlow<RestoreAskRequest?> = _askRequest

    private val _total = MutableStateFlow(ConflictPreview())
    val total: StateFlow<ConflictPreview> = _total

    private val _skippedDirs = MutableStateFlow(0)
    /** 因用户在预览处取消而跳过的目录数。 */
    val skippedDirs: StateFlow<Int> = _skippedDirs

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var rememberedChoice: Boolean? = null

    @Volatile
    private var askDeferred: CompletableDeferred<RestoreAskDecision?>? = null

    /** 用户勾了「本次全部按这个来」后记住的策略；非空时 ASK 不再弹窗。 */
    @Volatile
    private var rememberedStrategy: RestoreConflictStrategy? = null

    /** 本次任务上一次在 ASK 弹窗里选的策略，用于下一次弹窗的默认项。 */
    @Volatile
    private var lastStrategy: RestoreConflictStrategy = RestoreConflictStrategy.OVERWRITE

    @Volatile
    private var askCancelled = false

    fun reset() {
        rememberedChoice = null
        // 用 complete 而不是 cancel：await() 的 CancellationException 会击穿
        // executeAtLeast 的裸 block()，把整个 onProcessing 一起取消掉。
        deferred?.complete(false)
        deferred = null
        _request.value = null
        askDeferred?.complete(null)
        askDeferred = null
        _askRequest.value = null
        rememberedStrategy = null
        lastStrategy = RestoreConflictStrategy.OVERWRITE
        askCancelled = false
        _total.value = ConflictPreview()
        _skippedDirs.value = 0
    }

    /** 本次任务是否已有人点过「取消」（预览确认通道）。 */
    fun isCancelled(): Boolean = rememberedChoice == false

    /** 本次任务是否已有人在「逐个问」弹窗点过取消。 */
    fun isAskCancelled(): Boolean = askCancelled

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
        if (askCancelled) return false
        rememberedChoice?.let { return it }
        val d = CompletableDeferred<Boolean>()
        deferred = d
        _request.value = request
        // 超时按「取消整单」处理：清掉弹窗与 deferred，剩余目录一律跳过。
        return withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { d.await() } ?: run {
            decide(false)
            false
        }
    }

    /** UI 层回传用户决定，并记住，本次任务内不再弹窗。 */
    fun decide(confirmed: Boolean) {
        rememberedChoice = confirmed
        // 两条通道共用一个「整单已取消」语义，避免在预览处取消后又被 ASK 通道恢复。
        if (confirmed.not()) askCancelled = true
        _request.value = null
        deferred?.complete(confirmed)
        deferred = null
    }

    /**
     * 「逐个问」：Service 层为当前目录挂起，等待用户选一个具体策略。
     * 返回 null = 取消（跳过该目录，且本次任务剩余目录一并跳过）。
     * 若用户此前勾过「全部应用」，直接沿用该策略、不再弹窗。
     */
    suspend fun awaitStrategyDecision(request: RestoreAskRequest): RestoreAskDecision? {
        if (askCancelled || rememberedChoice == false) return null
        rememberedStrategy?.let { return RestoreAskDecision(it, applyToAll = true) }
        val d = CompletableDeferred<RestoreAskDecision?>()
        askDeferred = d
        _askRequest.value = request
        return withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { d.await() } ?: run {
            decideStrategy(null)
            null
        }
    }

    /** UI 层回传「逐个问」的决定；[decision] 为 null 表示取消。 */
    fun decideStrategy(decision: RestoreAskDecision?) {
        _askRequest.value = null
        if (decision == null) {
            askCancelled = true
            rememberedChoice = false
        } else {
            lastStrategy = decision.strategy
            if (decision.applyToAll) rememberedStrategy = decision.strategy
        }
        askDeferred?.complete(decision)
        askDeferred = null
    }

    /** 下一次 ASK 弹窗的默认选中项。 */
    fun lastChosenStrategy(): RestoreConflictStrategy = lastStrategy
}
