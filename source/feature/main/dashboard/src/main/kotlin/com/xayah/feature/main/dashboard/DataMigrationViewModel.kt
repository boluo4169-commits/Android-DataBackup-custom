package com.xayah.feature.main.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.model.database.CloudEntity
import com.xayah.feature.main.system.SystemDataCoordinator
import com.xayah.feature.main.system.SystemDataItem
import com.xayah.feature.main.system.SystemDataPending
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DataMigrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appsRepo: AppsRepo,
    private val filesRepo: FilesRepo,
    private val taskRepo: TaskRepository,
    cloudRepo: CloudRepository,
    private val systemData: SystemDataCoordinator,
) : ViewModel() {
    /** 已配置的云端账号，供一键恢复的备份源弹窗选择。 */
    val clouds: Flow<List<CloudEntity>> = cloudRepo.clouds

    /**
     * 一键备份：全选应用 + 文件，进入备份引导页。
     *
     * 系统数据在引导页点「继续」时由 BackupViewModelImpl 并行上传到当前激活云端账号，
     * 这里只负责重置上一轮结果。补了并发检查与异常兜底 —— 之前失败会静默、点了没反应。
     */
    fun oneClickBackup(onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val reason = withContext(Dispatchers.Default) {
                if (taskRepo.hasProcessingTask()) return@withContext "BUSY"
                runCatching {
                    appsRepo.activateAllForBackup()
                    filesRepo.activateAllForBackup()
                    systemData.resetBackupState()
                    ""
                }.getOrElse { "FAILED" }
            }
            withContext(Dispatchers.Main) {
                if (reason.isEmpty()) onReady() else onError(reason)
            }
        }
    }

    /**
     * 一键恢复准备阶段：把云端系统数据下载到私有缓存并做列差异检查。
     * 返回需要用户确认的项 —— **绝不自动跳过**，跨品牌恢复可能整表覆盖丢数据。
     * 本地源不恢复系统数据（系统数据备份口径为云端）。
     */
    suspend fun prepareOneClickRestore(cloudName: String): List<SystemDataPending> =
        withContext(Dispatchers.Default) {
            if (cloudName.isEmpty()) return@withContext emptyList()
            runCatching { systemData.prepareRestoreFromCloud(cloudName, SystemDataItem.values().toList()) }
                .getOrDefault(emptyList())
                .mapNotNull { it.pending }
        }

    suspend fun confirmSystemDataPending(pending: List<SystemDataPending>) = withContext(Dispatchers.Default) {
        pending.forEach { runCatching { systemData.applyPending(it) } }
        systemData.cleanup()
    }

    fun cancelSystemData() = systemData.cleanup()

    /**
     * 一键恢复收尾：全选所选备份源内的应用 + 文件。
     * 返回 null 表示成功，"BUSY"/"FAILED" 为失败原因。
     */
    suspend fun finishOneClickRestore(
        cloudName: String,
        backupDir: String,
        includeApps: Boolean,
        includeFiles: Boolean,
    ): String? = withContext(Dispatchers.Default) {
        if (taskRepo.hasProcessingTask()) return@withContext "BUSY"
        runCatching {
            // 先清空再按勾选重建，避免上一次流程的残留勾选混进来
            appsRepo.clearAllForRestore(cloudName, backupDir)
            filesRepo.clearAllForRestore()
            if (includeApps) appsRepo.activateAllForRestore(cloudName, backupDir)
            if (includeFiles) filesRepo.activateAllForRestore()
            null
        }.getOrElse { "FAILED" }
    }
}
