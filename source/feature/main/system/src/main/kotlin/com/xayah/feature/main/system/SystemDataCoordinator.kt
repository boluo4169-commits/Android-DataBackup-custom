package com.xayah.feature.main.system

import android.content.Context
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.util.LogUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一键流程里某一项系统数据的执行结果。 */
data class SystemDataTaskResult(
    val item: SystemDataItem,
    val success: Boolean,
    /** 非 null 表示此项需要用户确认列差异后才能继续（备份文件比本机少字段）。 */
    val pending: SystemDataPending? = null,
)

/** 等待用户确认的列差异恢复。staged 在本协调器自己的缓存目录里，不会被 Util 的 cleanupCache 清掉。 */
data class SystemDataPending(
    val item: SystemDataItem,
    val staged: String,
    val missingColumns: List<String>,
)

/** 一键备份里系统数据的整体状态，供引导页/完成页如实显示（不能只报「流程走完」）。 */
data class SystemDataBackupState(
    val running: Boolean = false,
    val cloudName: String = "",
    val results: List<SystemDataTaskResult> = emptyList(),
    val error: String? = null,
)

/**
 * 一键备份 / 一键恢复用的系统数据编排器（无 UI、无 ViewModel 状态）。
 *
 * 系统数据页 [SystemDataViewModel] 是「单项 + 手动选位置/账号」的交互流程，
 * 一键流程是「批量 + 固定云端账号」，两者共用底层 [SystemDataUtil] 但编排方式不同，
 * 因此单独抽这一层，避免把批量逻辑塞进页面 ViewModel，也避免复制一份云端逻辑。
 *
 * 云端目录固定为 `<remote>/system_data/`，文件名取 [SystemDataItem.suggestedName]，
 * 与系统数据页手动「备份到云端」完全一致 —— 一键恢复能读回手动备份的文件。
 */
@Singleton
class SystemDataCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: CloudRepository,
) {
    private fun log(msg: String) = LogUtil.log { "SystemDataCoordinator" to msg }

    /** 应用级 scope：系统数据上传必须在引导页/处理页切换后继续跑完，不能绑在某个 ViewModel 上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 本次一键流程要处理的系统数据项，默认全选，引导页可逐项开关。 */
    private val _selectedItems = MutableStateFlow(SystemDataItem.values().toSet())
    val selectedItems: StateFlow<Set<SystemDataItem>> = _selectedItems.asStateFlow()

    private val _backupState = MutableStateFlow(SystemDataBackupState())
    val backupState: StateFlow<SystemDataBackupState> = _backupState.asStateFlow()

    fun setItemSelected(item: SystemDataItem, selected: Boolean) {
        _selectedItems.value = if (selected) _selectedItems.value + item else _selectedItems.value - item
    }

    fun resetBackupState() {
        _backupState.value = SystemDataBackupState()
    }

    /** 在后台启动一次云端备份；调用方立刻返回，结果通过 [backupState] 观察。 */
    fun startBackup(cloudName: String) {
        val items = SystemDataItem.values().filter { it in _selectedItems.value }
        if (items.isEmpty()) {
            _backupState.value = SystemDataBackupState(cloudName = cloudName)
            return
        }
        if (cloudName.isEmpty()) {
            _backupState.value = SystemDataBackupState(cloudName = cloudName, error = "NO_CLOUD_ACCOUNT")
            return
        }
        _backupState.value = SystemDataBackupState(running = true, cloudName = cloudName)
        scope.launch {
            val results = runCatching { backupAllToCloud(cloudName, items) }
                .onFailure { log("startBackup failed: ${it.stackTraceToString()}") }
                .getOrDefault(items.map { SystemDataTaskResult(it, false) })
            _backupState.value = SystemDataBackupState(running = false, cloudName = cloudName, results = results)
        }
    }

    /** 独立的缓存目录：不放在 SystemDataUtil.tempDir 下，避免 restoreFromStaged 的 cleanupCache 误删其它待确认项。 */
    private fun cacheDir(): String = "${context.cacheDir}/one_click_system_data"

    private suspend fun srcOf(item: SystemDataItem): String = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.SmsDbPath
        SystemDataItem.CallLog -> SystemDataUtil.CallLogDbPath
        SystemDataItem.Wifi -> SystemDataUtil.resolveWifiStorePath()
    }

    private fun dstOf(item: SystemDataItem): String = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.SmsDbPath
        SystemDataItem.CallLog -> SystemDataUtil.CallLogDbPath
        SystemDataItem.Wifi -> ""
    }

    private fun pkgOf(item: SystemDataItem): String = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.SmsPackage
        SystemDataItem.CallLog -> SystemDataUtil.CallLogPackage
        SystemDataItem.Wifi -> ""
    }

    private fun remoteDir(entityRemote: String) = "${entityRemote}/${SystemDataUtil.CloudRelativeDir}"

    /**
     * 一键备份：逐项上传到指定云端账号。任一失败不影响其它项，结果逐项返回，
     * 调用方必须按结果如实汇报，不能只看「流程走完」就报成功。
     */
    suspend fun backupAllToCloud(cloudName: String, items: List<SystemDataItem>): List<SystemDataTaskResult> {
        if (items.isEmpty()) return emptyList()
        var dir = ""
        val connected = runCatching {
            withIOContext {
                cloudRepo.withClient(cloudName) { client, entity ->
                    dir = remoteDir(entity.remote)
                    // 刚配置的云端可能还没有这个目录，先逐级创建
                    client.mkdirRecursively(dir)
                }
            }
        }.onFailure { log("backupAllToCloud: connect failed: ${it.stackTraceToString()}") }.isSuccess
        if (!connected || dir.isEmpty()) {
            log("backupAllToCloud: cannot resolve cloud=$cloudName")
            return items.map { SystemDataTaskResult(it, false) }
        }
        return items.map { item ->
            val ok = runCatching {
                val staged = SystemDataUtil.stageToCache(context, srcOf(item), item.suggestedName)
                    ?: return@runCatching false
                var uploaded = false
                withIOContext {
                    cloudRepo.withClient(cloudName) { client, _ ->
                        // upload 成功后会删掉 src（也就是这个缓存文件），正好省一次清理
                        uploaded = cloudRepo.upload(client = client, src = staged, dstDir = dir).isSuccess
                    }
                }
                SystemDataUtil.cleanupCache(context)
                uploaded
            }.onFailure { log("backupAllToCloud: ${item.name} failed: ${it.stackTraceToString()}") }
                .getOrDefault(false)
            SystemDataTaskResult(item, ok)
        }
    }

    /**
     * 一键恢复：从云端下载选中的三项到独立缓存，逐项做列差异检查。
     * - 无差异：直接恢复，结果写进 success；
     * - 有差异：返回 pending 等调用方弹确认，**绝不自动跳过**（跨品牌恢复可能整表覆盖丢数据）。
     */
    suspend fun prepareRestoreFromCloud(cloudName: String, items: List<SystemDataItem>): List<SystemDataTaskResult> {
        if (items.isEmpty()) return emptyList()
        val dir = cacheDir()
        runCatching { File(dir).mkdirs() }
        return items.map { item ->
            val staged = runCatching {
                var downloaded: String? = null
                withIOContext {
                    cloudRepo.withClient(cloudName) { client, entity ->
                        val remote = "${remoteDir(entity.remote)}/${item.suggestedName}"
                        // 用 CloudRepository.exists 兼容分卷归档，不能用 client.exists
                        if (cloudRepo.exists(client, remote)) {
                            client.download(src = remote, dst = dir, onDownloading = { _, _ -> })
                            downloaded = "$dir/${item.suggestedName}"
                        } else {
                            log("prepareRestoreFromCloud: $remote not found")
                        }
                    }
                }
                downloaded
            }.onFailure { log("prepareRestoreFromCloud: ${item.name} failed: ${it.stackTraceToString()}") }
                .getOrNull()
            if (staged.isNullOrEmpty() || !File(staged!!).exists()) {
                return@map SystemDataTaskResult(item, false)
            }
            if (item == SystemDataItem.Wifi) {
                // WiFi 是解析后逐条重连，没有 schema 兼容问题
                return@map SystemDataTaskResult(item, SystemDataUtil.restoreWifiFromStaged(context, staged!!))
            }
            val missing = SystemDataUtil.findMissingColumns(context, staged!!, dstOf(item))
            if (missing.isEmpty()) {
                SystemDataTaskResult(
                    item,
                    SystemDataUtil.restoreFromStaged(context, staged!!, dstOf(item), pkgOf(item)),
                )
            } else {
                log("prepareRestoreFromCloud: ${item.name} needs confirm, ${missing.size} columns missing")
                SystemDataTaskResult(item, false, SystemDataPending(item, staged!!, missing))
            }
        }
    }

    /** 用户在列差异提示里点了「继续」。 */
    suspend fun applyPending(pending: SystemDataPending): Boolean {
        if (pending.item == SystemDataItem.Wifi) return false
        return SystemDataUtil.restoreFromStaged(
            context = context,
            staged = pending.staged,
            dst = dstOf(pending.item),
            pkg = pkgOf(pending.item),
        )
    }

    /** 用户取消，或整个流程结束后清掉本协调器自己的缓存。 */
    fun cleanup() {
        runCatching { File(cacheDir()).deleteRecursively() }
    }
}
