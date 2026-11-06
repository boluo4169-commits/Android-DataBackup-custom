package com.xayah.feature.main.processing.packages.restore

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.SavedStateHandle
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.util.formatSize
import com.xayah.core.network.client.getCloud
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.packages.restore.ProcessingServiceProxyCloudImpl
import com.xayah.core.service.packages.restore.ProcessingServiceProxyLocalImpl
import com.xayah.core.ui.material3.SnackbarDuration
import com.xayah.core.ui.material3.SnackbarType
import com.xayah.core.ui.model.DialogRadioItem
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.viewmodel.IndexUiEffect
import com.xayah.core.util.LogUtil
import com.xayah.core.util.decodeURL
import com.xayah.core.util.localBackupSaveDir
import com.xayah.core.util.navigateSingle
import com.xayah.feature.main.processing.AbstractPackagesProcessingViewModel
import com.xayah.feature.main.processing.FinishSetup
import com.xayah.feature.main.processing.GetUsers
import com.xayah.feature.main.processing.IndexUiState
import com.xayah.feature.main.processing.ProcessingUiIntent
import com.xayah.feature.main.processing.R
import com.xayah.feature.main.processing.SetCloudEntity
import com.xayah.feature.main.processing.UpdateApps
import com.xayah.feature.main.system.SystemDataCoordinator
import com.xayah.feature.main.system.SystemDataItem
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
class RestoreViewModelImpl @Inject constructor(
    @ApplicationContext private val mContext: Context,
    private val mRootService: RemoteRootService,
    mTaskRepo: TaskRepository,
    private val mPkgRepo: PackageRepository,
    private val mCloudRepo: CloudRepository,
    mLocalService: ProcessingServiceProxyLocalImpl,
    mCloudService: ProcessingServiceProxyCloudImpl,
    private val mSystemData: SystemDataCoordinator,
    private val args: SavedStateHandle,
) : AbstractPackagesProcessingViewModel(mContext, mRootService, mTaskRepo, mLocalService, mCloudService) {
    /** 一键恢复：应用恢复完成后自动接续文件恢复。 */
    val chainFileRestore: Boolean = args.get<Boolean>(MainRoutes.ARG_CHAIN_FILE_RESTORE) ?: false

    /** 原样保留路由编码参数，接续文件恢复图时复用，避免二次编码错配。 */
    val cloudNameArg: String = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME).orEmpty()
    val backupDirArg: String = args.get<String>(MainRoutes.ARG_ACCOUNT_REMOTE).orEmpty()

    /** 一键恢复引导页：系统数据三项可逐项勾选，状态与备份侧共用 @Singleton 协调器。 */
    val systemDataSelected = mSystemData.selectedItems
    val systemDataRestoreState = mSystemData.restoreState

    fun setSystemDataItemSelected(item: SystemDataItem, selected: Boolean) =
        mSystemData.setItemSelected(item, selected)

    /** 引导页点「继续」时启动系统数据恢复；本地源（账号名为空/空格）不恢复系统数据。 */
    fun startSystemDataRestore() {
        val cloud = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim().orEmpty()
        mSystemData.resetRestoreState()
        mSystemData.startRestore(cloud)
    }

    override suspend fun onOtherEvent(state: IndexUiState, intent: ProcessingUiIntent) {
        when (intent) {
            is UpdateApps -> {
                val cloud: String
                val backupSaveDir: String
                if (uiState.value.cloudEntity == null) {
                    cloud = ""
                    backupSaveDir = mContext.localBackupSaveDir()
                } else {
                    cloud = uiState.value.cloudEntity!!.name
                    backupSaveDir = uiState.value.cloudEntity!!.remote
                }
                val packages = mPkgRepo.queryActivated(OpType.RESTORE, cloud, backupSaveDir)
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Query activated apps, cloud: $cloud, backupDir: $backupSaveDir" }
                LogUtil.log { "RestoreViewModelImpl.UpdateApps" to "Queried apps count: ${packages.size}" }
                var bytes = 0.0
                packages.forEach {
                    // 只统计归档中实际存在的类型（dataStates 记录了备份时的勾选），
                    // 否则会把未备份类型的体积也算进来（恢复实体的 displayStats 由 config json 反序列化，含未备份类型的原始体积）
                    bytes += it.selectedDisplayStatsBytes
                }
                _packages.value = packages
                _packagesSize.value = bytes.formatSize()
            }

            is SetCloudEntity -> {
                val name = args.get<String>(MainRoutes.ARG_ACCOUNT_NAME)?.decodeURL()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    emitState(state.copy(storageIndex = 1, storageType = StorageMode.Cloud, cloudEntity = mCloudRepo.queryByName(name)))
                } else {
                    emitState(state.copy(storageIndex = 0, storageType = StorageMode.Local, cloudEntity = null))
                }
            }

            is FinishSetup -> {
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
                            intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
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
                        intent.navController.navigateSingle(MainRoutes.PackagesRestoreProcessing.route)
                    }
                }
            }

            is GetUsers -> {
                // A: 「恢复用户」是恢复**目标**，必须真实存在——否则 pm install --user N、
                // /data/user/N、appops --user N 全部失败。因此只列系统当前用户，不再并入备份记录里的
                // 历史 userId（空间被删除后记录仍残留，选中它 = 注定失败）。
                // 跨空间恢复能力保留：把目标显式选成存在的用户（如主空间 0）即可。
                val users = mRootService.getUsers().map { it.id }.toMutableSet()
                val restoreUsers = mutableListOf(
                    DialogRadioItem(
                        enum = Any(),
                        title = mContext.getString(R.string.backup_user),
                    )
                )
                users.sorted().forEach {
                    restoreUsers.add(
                        DialogRadioItem(
                            enum = Any(),
                            title = it.toString(),
                        )
                    )
                }
                _restoreUsers.value = restoreUsers
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
    private val _packages: MutableStateFlow<List<PackageEntity>> = MutableStateFlow(listOf())
    private val _packagesSize: MutableStateFlow<String> = MutableStateFlow("")
    private val _restoreUsers: MutableStateFlow<List<DialogRadioItem<Any>>> = MutableStateFlow(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))

    val accounts: StateFlow<List<DialogRadioItem<Any>>> = _accounts.stateInScope(listOf())
    val isTesting: StateFlow<Boolean> = _isTesting.stateInScope(false)
    val packages: StateFlow<List<PackageEntity>> = _packages.stateInScope(listOf())
    val packagesSize: StateFlow<String> = _packagesSize.stateInScope("")
    val restoreUsers: StateFlow<List<DialogRadioItem<Any>>> = _restoreUsers.stateInScope(listOf(DialogRadioItem(enum = Any(), title = mContext.getString(R.string.backup_user))))
}