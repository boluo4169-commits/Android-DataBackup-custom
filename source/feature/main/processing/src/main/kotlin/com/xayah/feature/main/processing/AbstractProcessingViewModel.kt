package com.xayah.feature.main.processing

import android.content.Context
import android.view.SurfaceControlHidden
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.readScreenOffCountDown
import com.xayah.core.model.OperationState
import com.xayah.core.model.ProcessingType
import com.xayah.core.model.RestoreAskDecision
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.AbstractProcessingServiceProxy
import com.xayah.core.service.util.ChecksumConfirmation
import com.xayah.core.service.util.ChecksumMismatch
import com.xayah.core.service.util.IntegrityConfirmation
import com.xayah.core.service.util.IntegrityReport
import com.xayah.core.service.util.RestoreAskRequest
import com.xayah.core.service.util.RestorePreviewConfirmation
import com.xayah.core.service.util.RestorePreviewRequest
import com.xayah.core.service.util.SsaidRestoreReminder
import com.xayah.core.model.ConflictPreview
import com.xayah.core.ui.model.ProcessingCardItem
import com.xayah.core.ui.model.ProcessingDataCardItem
import com.xayah.core.ui.util.addInfo
import com.xayah.core.ui.viewmodel.BaseViewModel
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.ui.viewmodel.UiIntent
import com.xayah.core.ui.viewmodel.UiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

data class IndexUiState(
    val state: OperationState,
    val storageIndex: Int,
    val storageType: StorageMode,
    val cloudEntity: CloudEntity?,
) : UiState

open class ProcessingUiIntent : UiIntent {
    data object Process : ProcessingUiIntent()
    data object Initialize : ProcessingUiIntent()
    data object DestroyService : ProcessingUiIntent()
    data object TurnOffScreen : ProcessingUiIntent()
}

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
abstract class AbstractProcessingViewModel(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    private val mTaskRepo: TaskRepository,
    private val mLocalService: AbstractProcessingServiceProxy,
    private val mCloudService: AbstractProcessingServiceProxy,
) : BaseViewModel<IndexUiState, ProcessingUiIntent, IndexUiEffect>(
    IndexUiState(
        state = OperationState.IDLE,
        storageIndex = 0,
        storageType = StorageMode.Local,
        cloudEntity = null
    )
) {
    open suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {}

    /**
     * 绑定服务前补全备份源。
     *
     * 一键恢复会在文件图上带 `skipSetup=true` 直接进入处理页，而 `SetCloudEntity`
     * 只写在 Setup 页的 LaunchedEffect 里 —— 路由参数明明带了云端账号，却没人把它
     * 读进 storageType，处理页就会按默认的 Local 绑定本地服务，云端文件恢复得到 0 项。
     * 需要读 SavedStateHandle 的子类重写本方法，在 Initialize 之前把状态补齐。
     */
    protected open suspend fun ensureStorageSource() {}

    init {
        mRootService.onFailure = {
            val msg = it.message
            if (msg != null)
                emitEffectOnIO(IndexUiEffect.ShowSnackbar(message = msg))
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is ProcessingUiIntent.Initialize -> {
                ensureStorageSource()
                _taskId.value = if (uiState.value.storageType == StorageMode.Cloud) mCloudService.initialize() else mLocalService.initialize()
            }

            is ProcessingUiIntent.Process -> {
                emitState(state.copy(state = OperationState.PROCESSING))
                if (state.storageType == StorageMode.Cloud) {
                    // Cloud
                    mCloudService.preprocessing()
                    mCloudService.processing()
                    mCloudService.postProcessing()
                    mCloudService.destroyService()
                } else {
                    // Local
                    mLocalService.preprocessing()
                    mLocalService.processing()
                    mLocalService.postProcessing()
                    mLocalService.destroyService()
                }
                emitState(state.copy(state = OperationState.DONE))
            }

            is ProcessingUiIntent.DestroyService -> {
                if (state.storageType == StorageMode.Cloud) {
                    // Cloud
                    mCloudService.destroyService(true)
                } else {
                    // Local
                    mLocalService.destroyService(true)
                }
            }

            is ProcessingUiIntent.TurnOffScreen -> {
                if (uiState.value.state == OperationState.PROCESSING) {
                    mRootService.setScreenOffTimeout(Int.MAX_VALUE)
                    mRootService.setDisplayPowerMode(SurfaceControlHidden.POWER_MODE_OFF)
                }
            }

            else -> {
                onOtherEvent(state, intent)
            }
        }
    }

    protected abstract val _dataItems: Flow<List<ProcessingDataCardItem>>

    protected val _taskId: MutableStateFlow<Long> = MutableStateFlow(-1)
    private var _task: Flow<TaskEntity?> = _taskId.flatMapLatest { id -> mTaskRepo.queryTaskFlow(id).flowOnIO() }
    private val _preItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private var _preItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.PREPROCESSING)
            .map { infoList ->
                _preItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _postItemsProgress: MutableStateFlow<Float> = MutableStateFlow(0F)
    private val _postItems: Flow<List<ProcessingCardItem>> = _taskId.flatMapLatest { id ->
        mTaskRepo.queryProcessingInfoFlow(id, ProcessingType.POST_PROCESSING)
            .map { infoList ->
                _postItemsProgress.value = infoList.sumOf { it.progress.toDouble() }.toFloat() / infoList.size
                val items = mutableListOf<ProcessingCardItem>()
                infoList.forEach {
                    items.addInfo(it)
                }
                items
            }
            .flowOnIO()
    }
    private val _screenOffCountDown = mContext.readScreenOffCountDown().flowOnIO()

    val task: StateFlow<TaskEntity?> = _task.stateInScope(null)
    val preItemsProgress: StateFlow<Float> = _preItemsProgress.stateInScope(0F)
    val preItems: StateFlow<List<ProcessingCardItem>> = _preItems.stateInScope(listOf())
    val dataItems: StateFlow<List<ProcessingDataCardItem>> by lazy { _dataItems.stateInScope(listOf()) }
    val postItemsProgress: StateFlow<Float> = _postItemsProgress.stateInScope(0F)
    val postItems: StateFlow<List<ProcessingCardItem>> = _postItems.stateInScope(listOf())
    val screenOffCountDown: StateFlow<Int> = _screenOffCountDown.stateInScope(0)

    /**
     * 归档 MD5 校验失败时的确认请求（由 service 层挂起等待，UI 层弹窗后回传决定）。
     */
    val checksumRequest: StateFlow<ChecksumMismatch?> = ChecksumConfirmation.request

    /**
     * 用户在「校验失败」弹窗上选择后调用。
     */
    fun decideChecksum(forceRestore: Boolean) = ChecksumConfirmation.decide(forceRestore)

    /**
     * 恢复前 SSAID（Android id）相关提醒（由 service 层挂起等待，UI 层弹窗后回传决定）。
     * 值为提醒文本，非空表示需要弹窗。标题随场景变化（随机化提醒 / 多版本覆盖提醒），一并观察。
     */
    val ssaidReminder: StateFlow<String?> = SsaidRestoreReminder.message
    val ssaidReminderTitle: StateFlow<String?> = SsaidRestoreReminder.title

    /**
     * 用户在 SSAID 提醒弹窗上选择后调用。
     */
    fun decideSsaidReminder(confirmed: Boolean) = SsaidRestoreReminder.decide(confirmed)

    /**
     * 备份完整性检查报告（恢复前，由 service 层挂起等待，UI 层弹窗后回传决定）。
     * 非空表示有应用备份文件缺失，需要弹窗提示。
     */
    val integrityRequest: StateFlow<IntegrityReport?> = IntegrityConfirmation.request

    /**
     * 用户在「备份不完整」弹窗上选择后调用。[continueRestore] = true 表示继续恢复。
     */
    fun decideIntegrity(continueRestore: Boolean) = IntegrityConfirmation.decide(continueRestore)

    /**
     * 恢复前冲突预览（由 medium 恢复 Service 在解压前挂起等待，UI 层弹清单后回传决定）。
     * 非空表示需要向用户展示「将被覆盖 / 跳过 / 留档」的清单。
     */
    val restorePreviewRequest: StateFlow<RestorePreviewRequest?> = RestorePreviewConfirmation.request

    /** 用户在预览弹窗上点「确认 / 取消」。 */
    fun decideRestorePreview(confirmed: Boolean) = RestorePreviewConfirmation.decide(confirmed)

    /** 「逐个问」弹窗请求（策略 = ASK 时由 medium 恢复 Service 在解压前挂起等待）。 */
    val restoreAskRequest: StateFlow<RestoreAskRequest?> = RestorePreviewConfirmation.askRequest

    /** 用户在「逐个问」弹窗里选了策略，或点了取消（null）。 */
    fun decideRestoreAsk(decision: RestoreAskDecision?) = RestorePreviewConfirmation.decideStrategy(decision)

    /** 本次恢复累计的逐项统计，供完成页展示（不只是「成功 1/1」）。 */
    val restorePreviewTotal: StateFlow<ConflictPreview> = RestorePreviewConfirmation.total

    /** 因预览处取消而跳过的目录数。 */
    val restorePreviewSkippedDirs: StateFlow<Int> = RestorePreviewConfirmation.skippedDirs
}
