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
     * 系统数据在引导页点「继续」时由 BackupViewModelImpl 并行上传到当前激活云端账号。
     * 这里重置上一轮结果，并把系统数据勾选恢复成默认全选 —— 否则上次关掉三项后，
     * 应用/文件每次都重新全选而系统数据仍是 0/3，会静默漏备份。
     * 补了并发检查与异常兜底 —— 之前失败会静默、点了没反应。
     */
    fun oneClickBackup(onReady: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val reason = withContext(Dispatchers.Default) {
                if (taskRepo.hasProcessingTask()) return@withContext "BUSY"
                runCatching {
                    appsRepo.activateAllForBackup()
                    filesRepo.activateAllForBackup()
                    systemData.resetBackupState()
                    systemData.resetSelection()
                    ""
                }.getOrElse { "FAILED" }
            }
            withContext(Dispatchers.Main) {
                if (reason.isEmpty()) onReady() else onError(reason)
            }
        }
    }

    /**
     * 一键恢复收尾：全选所选备份源内的应用 + 文件。
     * 返回 null 表示成功，"BUSY"/"FAILED" 为失败原因。
     */
    suspend fun finishOneClickRestore(cloudName: String, backupDir: String): String? =
        withContext(Dispatchers.Default) {
            if (taskRepo.hasProcessingTask()) return@withContext "BUSY"
            // 云端/本地备份清单可能还没扫进数据库：此时下面的「全选」是对着空表执行，
            // 引导页会显示 0.00 Bytes，用户必须进列表页手点「刷新」才看得到应用。
            // 源内没有任何恢复记录时先同步扫一次，扫完再全选。扫描失败不阻断流程，
            // 列表页进入时还会按当前源自动再刷一次。
            runCatching {
                if (appsRepo.hasRestoreRecords(cloudName, backupDir).not()) {
                    appsRepo.load(cloudName.ifEmpty { null }) { _, _, _ -> }
                    filesRepo.load(cloudName.ifEmpty { null }) { _, _, _ -> }
                }
            }
            runCatching {
                // 先清空再全选，避免上一次流程的残留勾选混进来；具体微调在恢复引导页里做
                appsRepo.clearAllForRestore(cloudName, backupDir)
                filesRepo.clearAllForRestore()
                appsRepo.activateAllForRestore(cloudName, backupDir)
                filesRepo.activateAllForRestore()
                // 与一键备份同口径：恢复引导页的系统数据也回到默认全选，不沿用上一轮的关闭状态
                systemData.resetSelection()
                null
            }.getOrElse { "FAILED" }
        }
}
