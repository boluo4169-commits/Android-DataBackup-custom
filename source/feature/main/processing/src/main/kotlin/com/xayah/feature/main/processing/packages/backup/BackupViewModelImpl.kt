package com.xayah.feature.main.processing.packages.backup

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.saveCloudActivatedAccountName
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.backup.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.backup.ProcessingServiceProxyLocalImpl
import androidx.lifecycle.SavedStateHandle
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.LogUtil
import com.xayah.core.util.navigateSingle
import com.xayah.core.util.maybePopBackAndNavigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@ExperimentalCoroutinesApi
@ExperimentalMaterial3Api
@HiltViewModel
class BackupViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    savedStateHandle: SavedStateHandle,
    mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mPkgRepo: PackageRepository,
    private val mCloudRepo: CloudRepository,
    private val mAppsRepo: AppsRepo,
    private val mFilesRepo: FilesRepo,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {

    /** 一键备份流程：应用备份完成后自动接续文件备份（由路由参数传入） */
    val chainFileBackup: Boolean = savedStateHandle.get<Boolean>(MainRoutes.ARG_CHAIN_FILE_BACKUP) ?: false

    /**
     * 上次扫描时的勾选签名（已激活 id 集合）。引导页的 UpdateApps 由 LaunchedEffect(null) 驱动，
     * 而「点进应用/文件列表微调勾选 → 返回」会让引导页重新进入组合、再次触发 —— 只要勾选没变，
     * 就没必要把那几十上百个应用/文件的 root 目录遍历再跑一遍（旧值本来就在 StateFlow 里，
     * ViewModel 挂在 nav backstack entry 上不会因返回而销毁）。
     */
    private var lastAppSignature: Set<Long>? = null
    private var lastFileSignature: Set<Long>? = null

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                // 两次查询本身很廉价（只读 DB）；昂贵的是下面逐项的 root IPC 体积统计
                val packages = mPkgRepo.queryActivated(OpType.BACKUP)
                val files = mFilesRepo.queryActivatedBackupFiles()
                val appSignature = packages.map { it.id }.toSet()
                val fileSignature = files.map { it.id }.toSet()
                // 勾选集合未变：沿用上次算好的值，直接返回（界面无 loading、无重复 root 遍历）
                if (appSignature == lastAppSignature && fileSignature == lastFileSignature) return
                lastAppSignature = appSignature
                lastFileSignature = fileSignature

                _isUpdating.value = true
                val startAt = System.currentTimeMillis()
                // 扫描时跳过了存储统计（storageStats 恒 0），这里计算选中应用的本地实际大小（displayStats），
                // 否则引导页「应用」总大小会显示 0.00 bytes。
                // 只算「还没算过」的：calculateLocalAppSize 会把结果 upsert 回库，已有值的直接复用，
                // 这样「改了勾选再回来」只算增量，而不是全量重算。
                var appComputed = 0
                packages.forEach { app ->
                    if (app.displayStatsBytes <= 0) {
                        mAppsRepo.calculateLocalAppSize(app)
                        appComputed++
                    }
                }
                var bytes = 0.0
                packages.forEach {
                    bytes += it.displayStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
                // 「文件」行：文件备份页配置的目录（一键备份/定时备份会一并备份），同样算实际大小
                var fileComputed = 0
                files.forEach { file ->
                    if (file.mediaInfo.displayBytes <= 0) {
                        mFilesRepo.calculateLocalFileSize(file)
                        fileComputed++
                    }
                }
                var fileBytes = 0.0
                files.forEach {
                    fileBytes += it.mediaInfo.displayBytes
                }
                _filesSize.value = fileBytes.formatSize()
                // 计时日志：便于在真机上对照「首次进入 vs 返回引导页」的耗时（返回时应 computed=0 且毫秒级）
                LogUtil.log {
                    "BackupViewModelImpl.UpdateApps" to
                            "apps=${packages.size} appComputed=$appComputed files=${files.size} fileComputed=$fileComputed elapsed=${System.currentTimeMillis() - startAt}ms"
                }
                _isUpdating.value = false
            }

            is SetCloudEntity -> {
                mContext.saveCloudActivatedAccountName(intent.name)
                emitState(state.copy(cloudEntity = mCloudRepo.queryByName(intent.name)))
            }

            is FinishSetup -> {
                // 空 selection 保护：应用一个都没勾时跳过空的应用备份——
                // 有文件则直接进文件备份（一键备份只选文件的场景），两者都空则提示。
                val hasPackages = mPkgRepo.queryActivated(OpType.BACKUP).isNotEmpty()
                val hasFiles = mFilesRepo.queryActivatedBackupFiles().isNotEmpty()
                if (hasPackages.not() && hasFiles.not()) {
                    emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Error,
                            message = mContext.getString(R.string.nothing_to_backup),
                        )
                    )
                    return
                }
                if (hasPackages.not()) {
                    withMainContext {
                        intent.mainNavController?.maybePopBackAndNavigateSingle(MainRoutes.MediumBackupProcessingGraph.getRoute(skipSetup = true))
                    }
                    return
                }
                if (state.storageType == StorageMode.Cloud) {
                    _isTesting.value = true
                    emitEffect(IndexUiEffect.DismissSnackbar)
                    emitEffectOnIO(
                        IndexUiEffect.ShowSnackbar(
                            type = SnackbarType.Loading,
                            message = mCloudRepo.getString(R.string.processing),
                            duration = SnackbarDuration.Indefinite,
                        )
                    )
                    runCatching {
                        val client = state.cloudEntity!!.getCloud()
                        client.testConnection()
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        withMainContext {
                            intent.navController.popBackStack()
                            intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                        }
                    }.onFailure {
                        emitEffect(IndexUiEffect.DismissSnackbar)
                        if (it.localizedMessage != null)
                            emitEffectOnIO(IndexUiEffect.ShowSnackbar(type = SnackbarType.Error, message = it.localizedMessage!!, duration = SnackbarDuration.Long))
                    }
                    _isTesting.value = false
                } else {
                    withMainContext {
                        intent.navController.popBackStack()
                        intent.navController.navigateSingle(MainRoutes.PackagesBackupProcessing.route)
                    }
                }
            }

            else -> {

            }
        }
    }

    private val _accounts: Flow<List<DialogRadioItem<Any>>> = mCloudRepo.clouds.map { entities ->
        entities.map {
            DialogRadioItem(
                enum = Any(),
                title = it.name,
                desc = it.user,
            )
        }
    }.flowOnIO()
    private val _isTesting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val _isUpdating: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow("")
    private val _filesSize: MutableStateFlow<String> = MutableStateFlow("")

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope("")
    val filesSize: StateFlow<String> = _filesSize.stateInScope("")
}