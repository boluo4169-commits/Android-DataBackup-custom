package com.xayah.feature.main.system

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.data.repository.CloudRepository
import com.xayah.core.model.database.CloudEntity
import com.xayah.core.rootservice.util.withIOContext
import com.xayah.core.util.LogUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SystemDataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cloudRepo: CloudRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(value = SystemDataUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // 已配置的云账号列表（备份/恢复时供用户挑一个）
        viewModelScope.launch {
            cloudRepo.clouds.collect { clouds -> _uiState.update { it.copy(clouds = clouds) } }
        }
    }

    /**
     * 统一的执行外壳：置忙 → 跑 [block] → 解除忙 → 弹提示。
     * 挂在 viewModelScope 而不是页面协程上：恢复要停系统 provider、云操作要连远端，
     * 屏幕旋转导致页面重建时不能半途而废。
     */
    private fun run(doneResId: Int, block: suspend () -> Boolean) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val ok = runCatching { block() }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    busy = false,
                    message = context.getString(if (ok) doneResId else R.string.system_data_failed),
                    messageSeq = it.messageSeq + 1,
                )
            }
        }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun log(msg: String) = LogUtil.log { "SystemData" to msg }

    /**
     * 与 [run] 同构，但**成功时不出提示**：用于恢复的"准备阶段"。
     * 准备阶段的产出可能只是「挂着等用户确认列差异」，此时弹「恢复完成」是错的。
     */
    private fun runPending(block: suspend () -> Boolean) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val ok = runCatching { block() }.getOrDefault(false)
            _uiState.update { it.copy(busy = false) }
            if (ok.not()) {
                _uiState.update {
                    it.copy(
                        message = context.getString(R.string.system_data_failed),
                        messageSeq = it.messageSeq + 1,
                    )
                }
            }
        }
    }

    /** 该数据项在设备上的真实位置（WiFi 要按版本探测两个候选路径）。 */
    private suspend fun sourceOf(item: SystemDataItem): String = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.SmsDbPath
        SystemDataItem.CallLog -> SystemDataUtil.CallLogDbPath
        SystemDataItem.Wifi -> SystemDataUtil.resolveWifiStorePath()
    }

    // ---------------- 备份 ----------------

    /** 备份到用户通过文件管理器选定的位置。 */
    fun backupToLocal(item: SystemDataItem, uri: Uri) = run(R.string.system_data_backup_done) {
        SystemDataUtil.backupToUri(context = context, src = sourceOf(item), uri = uri)
    }

    /** 备份到指定云账号的 `<remote>/system_data/` 下。 */
    fun backupToCloud(item: SystemDataItem, cloudName: String) = run(R.string.system_data_backup_done) {
        // 缓存名必须用真实文件名：上传时它就是远端文件名，三项共用一个临时名会互相覆盖
        val staged = SystemDataUtil.stageToCache(context, sourceOf(item), item.suggestedName)
        if (staged == null) {
            // 云端失败原因必须留在日志里：连接超时、远端目录不存在、账号没配远端目录等
            // 都只在这里暴露，否则界面只弹一句「操作失败」，用户与我们都无从下手。
            log("backupToCloud: stageToCache failed, src=${sourceOf(item)}")
            return@run false
        }
        var uploaded = false
        runCatching {
            // 云端是网络操作：viewModelScope 默认跑在主线程，不切 IO 会抛
            // NetworkOnMainThreadException（CloudRepository.getClient 里的 connect 是网络调用）
            withIOContext {
                cloudRepo.withClient(cloudName) { client, entity ->
                    val remoteDir = "${entity.remote}/${SystemDataUtil.CloudRelativeDir}"
                    log("backupToCloud: cloud=$cloudName, remoteDir=$remoteDir")
                    // 刚配置的云端可能还没有这个目录，先逐级创建
                    client.mkdirRecursively(remoteDir)
                    // upload 成功后会删掉 src（也就是这个缓存文件），正好省一次清理
                    uploaded = cloudRepo.upload(client = client, src = staged, dstDir = remoteDir).isSuccess
                }
            }
        }.onFailure { log("backupToCloud failed: ${it.stackTraceToString()}") }
        SystemDataUtil.cleanupCache(context)
        uploaded
    }

    // ---------------- 恢复 ----------------

    /** 从用户通过文件管理器选定的备份文件恢复。 */
    fun restoreFromLocal(item: SystemDataItem, uri: Uri) = runPending {
        val staged = SystemDataUtil.stageFromUri(context, uri) ?: return@runPending false
        prepareRestore(item, staged)
    }

    /**
     * 逐个云账号确认该备份是否存在，结果写进 [SystemDataUiState.cloudAvailability]。
     * 用在「恢复」的账号选择面板上：不存在的账号置灰，用户不用点下去才知道云端没有这份。
     * 只在恢复场景调用 —— 备份是往远端写，本来就不需要它先存在。
     */
    fun checkCloudAvailability(item: SystemDataItem) {
        val clouds = _uiState.value.clouds
        _uiState.update { it.copy(cloudAvailability = emptyMap()) }
        if (clouds.isEmpty()) return
        viewModelScope.launch {
            clouds.forEach { cloud ->
                // withClient 的 block 返回 Unit，拿不到返回值，用外部变量承接
                var exists = false
                val queried = runCatching {
                    withIOContext {
                        cloudRepo.withClient(cloud.name) { client, entity ->
                            exists = client.exists("${entity.remote}/${SystemDataUtil.CloudRelativeDir}/${item.suggestedName}")
                        }
                    }
                }.onFailure { log("checkCloudAvailability failed: ${it.stackTraceToString()}") }.isSuccess
                log("checkCloudAvailability: cloud=${cloud.name}, item=${item.name}, queried=$queried, exists=$exists")
                // 只有真的问到了才写结果：连不上/超时属于"没问到"，不代表云端没有这份备份，
                // 那种情况保持未知（不置灰），免得把网络问题说成"该账号下没有此备份"。
                if (queried) {
                    _uiState.update { it.copy(cloudAvailability = it.cloudAvailability + (cloud.name to exists)) }
                }
            }
        }
    }

    /** 从指定云账号下载备份并恢复。 */
    fun restoreFromCloud(item: SystemDataItem, cloudName: String) = runPending {
        // 这里刻意不走 cloudRepo.download：它内部会先用 root 删掉目标目录再 mkdirs 重建，
        // 目录属主因此变成 root，应用进程往里面落文件时会被拒绝（实测 open failed: EACCES）。
        // 改成用应用身份建目录 + 直接调 client.download。
        SystemDataUtil.ensureTempDir(context)
        val dstDir = SystemDataUtil.tempDir(context)
        var downloaded: String? = null
        val fetched = runCatching {
            withIOContext {
                cloudRepo.withClient(cloudName) { client, entity ->
                    val remoteFile = "${entity.remote}/${SystemDataUtil.CloudRelativeDir}/${item.suggestedName}"
                    log("restoreFromCloud: cloud=$cloudName, remoteFile=$remoteFile")
                    if (client.exists(remoteFile).not()) error("Cloud backup not found: $remoteFile")
                    client.download(src = remoteFile, dst = dstDir, onDownloading = { _, _ -> })
                    downloaded = "$dstDir/${item.suggestedName}"
                }
            }
        }.onFailure { log("restoreFromCloud failed: ${it.stackTraceToString()}") }.isSuccess
        if (fetched.not()) {
            SystemDataUtil.cleanupCache(context)
            return@runPending false
        }
        val staged = downloaded ?: return@runPending false
        prepareRestore(item, staged)
    }

    /**
     * 恢复的准备阶段：数据库类数据先做一次**列差异检查**，有差异就交给用户确认，无差异直接恢复。
     * WiFi 不走覆盖（它是解析后逐条重连），没有 schema 兼容问题，直接执行。
     */
    private suspend fun prepareRestore(item: SystemDataItem, staged: String): Boolean {
        if (item == SystemDataItem.Wifi) {
            return SystemDataUtil.restoreWifiFromStaged(context = context, staged = staged)
        }
        val dst = dstOf(item)
        val missing = SystemDataUtil.findMissingColumns(context = context, stagedSource = staged, dst = dst)
        if (missing.isEmpty()) {
            log("prepareRestore: no schema gap for ${item.name}")
            return restoreFromStaged(item, staged)
        }
        log("prepareRestore: ${missing.size} columns missing in backup -> ${missing.take(20)}")
        // 挂起等用户确认；确认后走 confirmRestore()
        _uiState.update { it.copy(pendingRestore = PendingRestore(item = item, staged = staged, missingColumns = missing)) }
        return true
    }

    /** 用户在列差异提示里点了「继续」。 */
    fun confirmRestore() {
        val pending = _uiState.value.pendingRestore ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(pendingRestore = null, busy = true) }
        viewModelScope.launch {
            val ok = runCatching { restoreFromStaged(pending.item, pending.staged) }.getOrDefault(false)
            _uiState.update {
                it.copy(
                    busy = false,
                    message = context.getString(if (ok) R.string.system_data_restore_done else R.string.system_data_failed),
                    messageSeq = it.messageSeq + 1,
                )
            }
        }
    }

    /** 用户在列差异提示里取消：丢掉已经落到缓存的文件。 */
    fun cancelRestore() {
        if (_uiState.value.pendingRestore == null) return
        _uiState.update { it.copy(pendingRestore = null) }
        viewModelScope.launch { SystemDataUtil.cleanupCache(context) }
    }

    /** 该数据项在设备上的目标路径。 */
    private fun dstOf(item: SystemDataItem): String = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.SmsDbPath
        SystemDataItem.CallLog -> SystemDataUtil.CallLogDbPath
        SystemDataItem.Wifi -> ""
    }

    /** 本地与云端两条通路拿到缓存文件后，恢复动作完全一致。 */
    private suspend fun restoreFromStaged(item: SystemDataItem, staged: String): Boolean = when (item) {
        SystemDataItem.Sms -> SystemDataUtil.restoreFromStaged(
            context = context,
            staged = staged,
            dst = SystemDataUtil.SmsDbPath,
            pkg = SystemDataUtil.SmsPackage,
        )

        SystemDataItem.CallLog -> SystemDataUtil.restoreFromStaged(
            context = context,
            staged = staged,
            dst = SystemDataUtil.CallLogDbPath,
            pkg = SystemDataUtil.CallLogPackage,
        )

        SystemDataItem.Wifi -> SystemDataUtil.restoreWifiFromStaged(context = context, staged = staged)
    }
}

data class SystemDataUiState(
    val busy: Boolean = false,
    val message: String? = null,
    /** 自增序号，用于在 UI 侧触发 Snackbar（同一个文案连续出现两次也要能弹）。 */
    val messageSeq: Long = 0L,
    val clouds: List<CloudEntity> = emptyList(),
    /**
     * 恢复时各云账号下是否存在该备份（key = 账号名）。
     * 只放**已查完**的账号：不在 map 里表示还没查完 —— 网络慢时不至于把整片都置灰。
     */
    val cloudAvailability: Map<String, Boolean> = emptyMap(),
    /** 非 null 时表示有一次恢复正等用户确认（备份文件相对本机缺列） */
    val pendingRestore: PendingRestore? = null,
)

/**
 * 等待用户确认的恢复操作。
 * [missingColumns] 是「本机数据库有、而这份备份里没有」的列 —— 跨品牌恢复时通常非空。
 */
data class PendingRestore(
    val item: SystemDataItem,
    val staged: String,
    val missingColumns: List<String>,
)

/** 三项系统数据，以及备份文件在本地/云端的文件名。 */
enum class SystemDataItem(val suggestedName: String) {
    Sms("mmssms.db"),
    CallLog("calllog.db"),
    Wifi("WifiConfigStore.xml"),
}
