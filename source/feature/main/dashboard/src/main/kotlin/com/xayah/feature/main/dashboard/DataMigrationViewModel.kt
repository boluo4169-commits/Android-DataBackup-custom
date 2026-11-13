package com.xayah.feature.main.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.AppsRepo
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.data.repository.FilesRepo
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.datastore.saveCloudActivatedAccountName
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
            // 云端恢复服务 RestoreServiceCloudImpl.getMedium() 走 CloudRepository.getClient()，
            // 读的是 DataStore 里的「全局激活账号」，而不是一键恢复弹窗选中的源。恢复主页
            // 手动选账号时 SetCloudEntity 会同步这个值，一键恢复绕过了那一页，必须在这里补上，
            // 否则多账号场景下会连到上一个账号（本轮只有一个「家」所以没暴露）。
            // 本地源传空串，与恢复主页选「本地」的口径一致。
            runCatching { context.saveCloudActivatedAccountName(cloudName) }
            // 云端/本地备份清单可能还没扫进数据库：此时下面的「全选」是对着空表执行，
            // 引导页会显示 0.00 Bytes，用户必须进列表页手点「刷新」才看得到条目。
            // 应用与文件必须各自判断：源里已有应用记录不代表文件清单也扫过（例如之前只
            // 备份过应用），共用 appsRepo 的判断会让文件恢复永远查不到记录。
            // 扫描失败不阻断流程，列表页进入时还会按当前源自动再刷一次。
            runCatching {
                if (appsRepo.hasRestoreRecords(cloudName, backupDir).not()) {
                    appsRepo.load(cloudName.ifEmpty { null }) { _, _, _ -> }
                }
                if (filesRepo.hasRestoreRecords(cloudName, backupDir).not()) {
                    filesRepo.load(cloudName.ifEmpty { null }) { _, _, _ -> }
                }
            }
            runCatching {
                // 先清空再全选，避免上一次流程的残留勾选混进来；具体微调在恢复引导页里做。
                // 两侧都按所选源过滤，不整表操作。
                appsRepo.clearAllForRestore(cloudName, backupDir)
                filesRepo.clearAllForRestore(cloudName, backupDir)
                appsRepo.activateAllForRestore(cloudName, backupDir)
                filesRepo.activateAllForRestore(cloudName, backupDir)
                // 与一键备份同口径：恢复引导页的系统数据也回到默认全选，不沿用上一轮的关闭状态
                systemData.resetSelection()
                // 同时清掉上一轮恢复的逐项结果，避免完成页把旧流程的结果误报成本次。
                systemData.resetRestoreState()
                null
            }.getOrElse { "FAILED" }
        }
}
